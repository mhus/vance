package de.mhus.vance.anus.compose;

import de.mhus.vance.anus.BuildInfo;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders {@code docker-compose.yml}, the managed {@code .env} key set, the
 * front-door {@code Caddyfile} and an operator {@code README.md} from a
 * {@link ComposeSetupState}.
 *
 * <p>The compose file is assembled from typed service fragments rather than a
 * templating engine. Topology:
 * <ul>
 *   <li>{@code caddy} is <b>always</b> the single published front door — every
 *       request enters on one port and Caddy reverse-proxies to the face (which
 *       in turn splits {@code /brain/*} REST + WebSocket to the brain). Like the
 *       Mini deployment, but local and, by default, without TLS.</li>
 *   <li>{@code mongodb}/{@code brain}/{@code face} are internal to the compose
 *       network; their host ports are published only on an expert opt-in.</li>
 *   <li>{@code redis} joins when live features are on; the debug UIs
 *       (mongo-express + redis-commander) and the optional {@code anus} admin
 *       service ride along under Compose {@code profiles}.</li>
 * </ul>
 *
 * <p>Caddy's listen address rides through {@code .env} as {@code VANCE_SITE_ADDRESS}:
 * {@code :80} for plain HTTP (local, or external behind an upstream TLS
 * terminator like ngrok), or the bare hostname for auto-provisioned HTTPS.
 */
final class ComposeFileRenderer {

    private ComposeFileRenderer() {}

    // ──────────────────────── derived values ────────────────────────

    /** Browser-facing base URL the Brain uses for callbacks / deep links. */
    static String publicBaseUrl(ComposeSetupState s) {
        if (s.isExternalAccess() && !s.getExternalUrl().isBlank()) {
            return stripTrailingSlash(s.getExternalUrl().strip());
        }
        return "http://localhost:" + s.getFacePort();
    }

    /** Whether auth cookies get the {@code Secure} flag (HTTPS origins only). */
    static boolean cookiesSecure(ComposeSetupState s) {
        return s.isExternalAccess()
                && s.getExternalUrl().strip().toLowerCase().startsWith("https://");
    }

    /** Bare host (no scheme, no path) for Caddy's auto-HTTPS site address. */
    static String externalHost(ComposeSetupState s) {
        String url = s.getExternalUrl().strip();
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            url = url.substring(scheme + 3);
        }
        int slash = url.indexOf('/');
        if (slash >= 0) {
            url = url.substring(0, slash);
        }
        return url;
    }

    /** External access with Caddy terminating TLS (auto-HTTPS for the domain). */
    static boolean caddyTerminatesTls(ComposeSetupState s) {
        return s.isExternalAccess() && s.isCaddyTls();
    }

    /** Caddy's {@code --from} listen address: a hostname (auto-HTTPS) or {@code :80} (plain). */
    static String siteAddress(ComposeSetupState s) {
        return caddyTerminatesTls(s) ? externalHost(s) : ":80";
    }

    private static String stripTrailingSlash(String v) {
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    // ──────────────────────── .env ────────────────────────

    /** Builds the ordered map of {@code .env} keys the wizard manages. */
    static Map<String, String> renderEnv(ComposeSetupState s) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("VANCE_IMAGE_NAMESPACE", s.getImageNamespace());
        env.put("IMAGE_TAG", s.getImageTag());

        env.put("MONGO_INITDB_ROOT_USERNAME", s.getMongoUser());
        env.put("MONGO_INITDB_ROOT_PASSWORD", s.getMongoPassword());
        env.put("VANCE_MONGODB_DATABASE", s.getMongoDatabase());
        env.put("MONGO_PORT", Integer.toString(s.getMongoPort()));

        env.put("VANCE_ENCRYPTION_PASSWORD", s.getEncryptionPassword());
        env.put("VANCE_INTERNAL_TOKEN", s.getInternalToken());
        env.put("VANCE_BOOTSTRAP_ACME", "false");
        env.put("VANCE_FOOK_ENABLED", Boolean.toString(s.isFookEnabled()));
        env.put("BRAIN_PORT", Integer.toString(s.getBrainPort()));
        env.put("BRAIN_JAVA_OPTS", "-XX:+UseG1GC -XX:MaxRAMPercentage=75.0");

        env.put("VANCE_DEFAULT_LANGUAGE", s.getLanguageName());
        env.put("VANCE_DEFAULT_LANGUAGE_CODE", s.getLanguageCode());

        env.put("VANCE_REDIS_ENABLED", Boolean.toString(s.isRedisEnabled()));
        env.put("REDIS_PORT", Integer.toString(s.getRedisPort()));

        // The single published front-door port + Caddy's listen address.
        env.put("VANCE_PORT", Integer.toString(s.getFacePort()));
        env.put("VANCE_SITE_ADDRESS", siteAddress(s));

        // Access mode + browser-facing URL (Brain callbacks / cookie security).
        env.put("VANCE_ACCESS_MODE", s.isExternalAccess() ? "external" : "local");
        env.put("VANCE_WEB_PUBLICBASEURL", publicBaseUrl(s));
        env.put("VANCE_WEB_COOKIES_SECURE", Boolean.toString(cookiesSecure(s)));
        if (s.isExternalAccess()) {
            env.put("VANCE_EXTERNAL_URL", stripTrailingSlash(s.getExternalUrl().strip()));
            env.put("VANCE_CADDY_TLS", s.isCaddyTls() ? "auto" : "off");
        }

        // Host-port exposure toggles (round-tripped so a re-run restores them —
        // the wizard reconstructs its state from .env, it does not parse the
        // compose file).
        env.put("VANCE_EXPOSE_BRAIN", Boolean.toString(s.isExposeBrainPort()));
        env.put("VANCE_EXPOSE_MONGO", Boolean.toString(s.isExposeMongoPort()));
        env.put("VANCE_EXPOSE_REDIS", Boolean.toString(s.isExposeRedisPort()));
        env.put("VANCE_ANUS_SERVICE", Boolean.toString(s.isAnusServiceEnabled()));

        env.put("VANCE_ANUS_PASSWORD_HASH", s.getAnusPasswordHash());

        if (s.isToolsEnabled()) {
            env.put("MONGO_EXPRESS_USERNAME", s.getMongoExpressUser());
            env.put("MONGO_EXPRESS_PASSWORD", s.getMongoExpressPassword());
            env.put("MONGO_EXPRESS_PORT", Integer.toString(s.getMongoExpressPort()));
            env.put("REDIS_UI_PORT", Integer.toString(s.getRedisUiPort()));
        }
        return env;
    }

    // ──────────────────────── docker-compose.yml ────────────────────────

    static String renderCompose(ComposeSetupState s) {
        StringBuilder sb = new StringBuilder();
        sb.append(header(s));

        sb.append(MONGODB);
        if (s.isExposeMongoPort()) {
            sb.append(MONGO_PORTS);
        }
        sb.append(MONGO_HEALTHCHECK);

        if (s.isRedisEnabled()) {
            sb.append(REDIS);
            if (s.isExposeRedisPort()) {
                sb.append(REDIS_PORTS);
            }
            sb.append(REDIS_HEALTHCHECK);
        }

        sb.append(s.isRedisEnabled() ? BRAIN_WITH_REDIS : BRAIN_NO_REDIS);
        if (s.isExposeBrainPort()) {
            sb.append(BRAIN_PORTS);
        }

        sb.append(FACE);

        // Caddy is the single published front door — always present.
        sb.append(CADDY_HEAD);
        sb.append(caddyTerminatesTls(s) ? CADDY_PORTS_TLS : CADDY_PORTS_HTTP);

        if (s.isToolsEnabled()) {
            sb.append(MONGO_EXPRESS);
            if (s.isRedisEnabled()) {
                sb.append(REDIS_COMMANDER);
            }
        }
        if (s.isAnusServiceEnabled()) {
            sb.append(ANUS);
        }
        return sb.toString();
    }

    private static String header(ComposeSetupState s) {
        return """
            # Generated by `vancetope-anus --setup-docker-compose`
            #   %s
            #
            # Quick start:
            #   docker compose up -d
            #   # first-time tenant + user + LLM: see README.md
            #   open %s
            #
            # Everything is served through Caddy on the single Vance port above;
            # brain/mongo/redis stay internal to the compose network.
            # Config lives in the sibling .env — edit it or re-run the wizard.
            # Persistent data (Mongo, Brain, Redis, …) is bind-mounted under ./data
            # next to this file — back that folder up, it survives `docker compose down`.

            name: vance

            services:
            """.formatted(BuildInfo.line(), publicBaseUrl(s));
    }

    // ──────────────────────── README.md ────────────────────────

    /**
     * Operator README written next to the stack: the build stamp of the wizard
     * that produced it, the first-run + first-time-setup commands, and the
     * everyday {@code docker compose} verbs. Replaces the previously generated
     * {@code setup.sh} — nothing in the running stack depends on it.
     */
    static String renderReadme(ComposeSetupState s) {
        String url = publicBaseUrl(s);
        StringBuilder sb = new StringBuilder();
        sb.append("""
                # Vance — Docker Compose

                Generated by `vancetope-anus --setup-docker-compose`
                `%s`

                Everything is served through Caddy on a single port; the brain,
                MongoDB and Redis stay internal to the compose network.

                - **Open:** %s
                - `docker-compose.yml` — the stack
                - `.env` — configuration + secrets (never commit)
                - `Caddyfile` — front-door routing (`/brain/*` → brain, rest → face)
                - `./data/` — persistent data (Mongo, Brain, Redis); survives `docker compose down`

                ## First run

                ```bash
                docker compose up -d
                ```

                First-time setup — create the tenant, admin user and LLM provider
                in a throwaway admin container against the running MongoDB:

                ```bash
                set -a; source .env; set +a
                docker run --rm -it --network "${COMPOSE_PROJECT_NAME:-vance}_default" \\
                  -e SPRING_PROFILES_ACTIVE=prod \\
                  -e VANCE_MONGODB_URI="mongodb://$MONGO_INITDB_ROOT_USERNAME:$MONGO_INITDB_ROOT_PASSWORD@mongodb:27017/$VANCE_MONGODB_DATABASE?authSource=admin" \\
                  -e VANCE_MONGODB_DATABASE="$VANCE_MONGODB_DATABASE" \\
                  -e VANCE_ENCRYPTION_PASSWORD="$VANCE_ENCRYPTION_PASSWORD" \\
                  -e VANCE_ANUS_BRAIN_HTTPBASE=http://brain:9990 \\
                  -e VANCE_DEFAULT_LANGUAGE="$VANCE_DEFAULT_LANGUAGE" \\
                  -e VANCE_DEFAULT_LANGUAGE_CODE="$VANCE_DEFAULT_LANGUAGE_CODE" \\
                  "$VANCE_IMAGE_NAMESPACE/vancetope-anus:$IMAGE_TAG" --setup
                ```

                Any anus admin command that reaches the brain (e.g.
                `project-kits import`) needs the same `--network` and
                `-e VANCE_ANUS_BRAIN_HTTPBASE=http://brain:9990`, and a running
                brain (`docker compose up -d`).

                Then open %s and log in.

                ## Everyday commands

                ```bash
                docker compose ps                # status of all services
                docker compose logs -f           # follow all logs
                docker compose logs -f brain     # follow one service
                docker compose stop              # stop the stack (data kept)
                docker compose start             # start it again
                docker compose restart brain     # restart a single service
                docker compose down              # remove containers (./data is kept)
                docker compose pull && docker compose up -d   # update to newer images
                ```
                """.formatted(BuildInfo.line(), url, url));

        if (s.isToolsEnabled()) {
            sb.append("""

                    ## Debug tools

                    ```bash
                    docker compose --profile tools up -d
                    ```
                    """);
            sb.append("- mongo-express: http://localhost:").append(s.getMongoExpressPort()).append('\n');
            if (s.isRedisEnabled()) {
                sb.append("- redis-commander: http://localhost:").append(s.getRedisUiPort()).append('\n');
            }
        }
        if (s.isAnusServiceEnabled()) {
            sb.append("""

                    ## Admin shell

                    The persistent anus service is enabled. Open the interactive
                    admin REPL with:

                    ```bash
                    docker compose run --rm anus
                    ```
                    """);
        }
        sb.append("""

                ## Reconfigure

                Re-run the wizard (the same `docker run … --setup-docker-compose`
                command that produced this folder), or edit `.env` by hand, then
                `docker compose up -d`.
                """);
        return sb.toString();
    }

    // ──────────────────────── Caddyfile ────────────────────────

    /**
     * The front-door Caddyfile — a compose-local mirror of the Mini deployment
     * (see {@code deployment/mini/caddy/eddie.mhus.de.caddy}): {@code /brain/*}
     * (REST + WebSocket) proxies to the brain, everything else to the face
     * static server. The site address comes from the {@code VANCE_SITE_ADDRESS}
     * env var Caddy sees ({@code :80} for plain HTTP, a hostname for auto-HTTPS).
     */
    static String renderCaddyfile() {
        return CADDYFILE;
    }

    // ──────────────────────── fragments ────────────────────────

    private static final String MONGODB = """

              mongodb:
                image: mongo:7.0
                restart: unless-stopped
                environment:
                  MONGO_INITDB_ROOT_USERNAME: ${MONGO_INITDB_ROOT_USERNAME:-root}
                  MONGO_INITDB_ROOT_PASSWORD: ${MONGO_INITDB_ROOT_PASSWORD:-example}
                  MONGO_INITDB_DATABASE: ${VANCE_MONGODB_DATABASE:-vance}
                volumes:
                  - ./data/mongo:/data/db
            """;

    private static final String MONGO_PORTS = """
                ports:
                  - "${MONGO_PORT:-27017}:27017"
            """;

    private static final String MONGO_HEALTHCHECK = """
                healthcheck:
                  test: ["CMD", "mongosh", "--quiet", "--eval", "db.runCommand({ ping: 1 }).ok"]
                  interval: 10s
                  timeout: 5s
                  retries: 10
                  start_period: 20s
            """;

    private static final String REDIS = """

              redis:
                image: redis:7-alpine
                restart: unless-stopped
                volumes:
                  - ./data/redis:/data
                command: ["redis-server", "--save", "60", "1", "--loglevel", "warning"]
            """;

    private static final String REDIS_PORTS = """
                ports:
                  - "${REDIS_PORT:-6379}:6379"
            """;

    private static final String REDIS_HEALTHCHECK = """
                healthcheck:
                  test: ["CMD", "redis-cli", "ping"]
                  interval: 10s
                  timeout: 3s
                  retries: 5
                  start_period: 5s
            """;

    private static final String BRAIN_ENV_COMMON = """
                  SPRING_PROFILES_ACTIVE: prod
                  VANCE_MONGODB_URI: mongodb://${MONGO_INITDB_ROOT_USERNAME:-root}:${MONGO_INITDB_ROOT_PASSWORD:-example}@mongodb:27017/${VANCE_MONGODB_DATABASE:-vance}?authSource=admin
                  VANCE_MONGODB_DATABASE: ${VANCE_MONGODB_DATABASE:-vance}
                  VANCE_ENCRYPTION_PASSWORD: ${VANCE_ENCRYPTION_PASSWORD:-changeit}
                  VANCE_INTERNAL_TOKEN: ${VANCE_INTERNAL_TOKEN:-changeit-internal}
                  VANCE_BOOTSTRAP_ACME: ${VANCE_BOOTSTRAP_ACME:-false}
                  VANCE_FOOK_ENABLED: ${VANCE_FOOK_ENABLED:-true}
                  VANCE_WEB_PUBLICBASEURL: ${VANCE_WEB_PUBLICBASEURL:-http://localhost:9999}
                  VANCE_WEB_COOKIES_SECURE: ${VANCE_WEB_COOKIES_SECURE:-false}
            """;

    private static final String BRAIN_TAIL = """
                  JAVA_OPTS: ${BRAIN_JAVA_OPTS:--XX:+UseG1GC -XX:MaxRAMPercentage=75.0}
                volumes:
                  - ./data/brain:/app/data
                  - ./data/brain-logs:/app/logs
            """;

    private static final String BRAIN_PORTS = """
                ports:
                  - "${BRAIN_PORT:-9990}:9990"
            """;

    private static final String BRAIN_NO_REDIS = """

              brain:
                image: ${VANCE_IMAGE_NAMESPACE:-mhus}/vancetope-brain:${IMAGE_TAG:-latest}
                restart: unless-stopped
                depends_on:
                  mongodb:
                    condition: service_healthy
                environment:
            """ + BRAIN_ENV_COMMON + BRAIN_TAIL;

    private static final String BRAIN_WITH_REDIS = """

              brain:
                image: ${VANCE_IMAGE_NAMESPACE:-mhus}/vancetope-brain:${IMAGE_TAG:-latest}
                restart: unless-stopped
                depends_on:
                  mongodb:
                    condition: service_healthy
                  redis:
                    condition: service_healthy
                environment:
            """ + BRAIN_ENV_COMMON + """
                  VANCE_REDIS_ENABLED: "true"
                  VANCE_REDIS_HOST: redis
                  VANCE_REDIS_PORT: "6379"
            """ + BRAIN_TAIL;

    // The face image serves the SPA and reverse-proxies /brain/* (REST + WS) to
    // the brain internally. It has no published host port — Caddy fronts it.
    private static final String FACE = """

              face:
                image: ${VANCE_IMAGE_NAMESPACE:-mhus}/vancetope-face:${IMAGE_TAG:-latest}
                restart: unless-stopped
                depends_on:
                  brain:
                    condition: service_started
            """;

    private static final String CADDY_HEAD = """

              # Single front door (see the sibling Caddyfile). Routes /brain/*
              # (REST + WebSocket) to the brain and everything else to the face
              # static server. VANCE_SITE_ADDRESS is :80 for plain HTTP (local /
              # upstream-TLS) or a hostname for auto-provisioned HTTPS.
              caddy:
                image: caddy:2
                restart: unless-stopped
                depends_on:
                  brain:
                    condition: service_started
                  face:
                    condition: service_started
                environment:
                  VANCE_SITE_ADDRESS: ${VANCE_SITE_ADDRESS:-:80}
                volumes:
                  - ./Caddyfile:/etc/caddy/Caddyfile:ro
                  - ./data/caddy:/data
                  - ./data/caddy-config:/config
            """;

    // Mirrors deployment/mini/caddy/eddie.mhus.de.caddy, retargeted at the
    // compose service names. {$VANCE_SITE_ADDRESS::80} = the env var with a :80
    // default (plain HTTP); a hostname there makes Caddy auto-provision TLS.
    private static final String CADDYFILE = """
            # Generated by `vancetope-anus --setup-docker-compose`
            #   %s
            #
            # Front door: /brain/* -> brain (REST + WebSocket), rest -> face.
            # Site address is injected via the VANCE_SITE_ADDRESS env var.

            {$VANCE_SITE_ADDRESS::80} {
                handle /brain/* {
                    reverse_proxy brain:9990 {
                        # flush_interval -1 disables response buffering (SSE /
                        # streaming); WebSocket upgrades are handled transparently.
                        flush_interval -1
                        transport http {
                            read_timeout 1h
                            write_timeout 1h
                        }
                    }
                }

                handle {
                    reverse_proxy face:80
                }

                request_body {
                    max_size 50MB
                }

                header -Server
            }
            """.formatted(BuildInfo.line());

    private static final String CADDY_PORTS_HTTP = """
                ports:
                  - "${VANCE_PORT:-9999}:80"
            """;

    private static final String CADDY_PORTS_TLS = """
                ports:
                  - "80:80"
                  - "443:443"
            """;

    private static final String MONGO_EXPRESS = """

              mongo-express:
                image: mongo-express:1.0
                restart: unless-stopped
                profiles: ["tools"]
                depends_on:
                  mongodb:
                    condition: service_healthy
                environment:
                  ME_CONFIG_MONGODB_ADMINUSERNAME: ${MONGO_INITDB_ROOT_USERNAME:-root}
                  ME_CONFIG_MONGODB_ADMINPASSWORD: ${MONGO_INITDB_ROOT_PASSWORD:-example}
                  ME_CONFIG_MONGODB_SERVER: mongodb
                  ME_CONFIG_BASICAUTH_USERNAME: ${MONGO_EXPRESS_USERNAME:-admin}
                  ME_CONFIG_BASICAUTH_PASSWORD: ${MONGO_EXPRESS_PASSWORD:-admin}
                ports:
                  - "${MONGO_EXPRESS_PORT:-9081}:8081"
            """;

    private static final String REDIS_COMMANDER = """

              redis-commander:
                image: rediscommander/redis-commander:latest
                restart: unless-stopped
                profiles: ["tools"]
                depends_on:
                  redis:
                    condition: service_healthy
                environment:
                  REDIS_HOSTS: local:redis:6379
                ports:
                  - "${REDIS_UI_PORT:-8082}:8081"
            """;

    private static final String ANUS = """

              # Persistent admin shell (expert opt-in). NOT needed for normal
              # operation and NOT started by `docker compose up`; first-time
              # setup runs as a one-shot container (see README.md). Invoke on
              # demand for the interactive admin REPL:
              #   docker compose run --rm anus
              anus:
                image: ${VANCE_IMAGE_NAMESPACE:-mhus}/vancetope-anus:${IMAGE_TAG:-latest}
                profiles: ["tools"]
                depends_on:
                  mongodb:
                    condition: service_healthy
                stdin_open: true
                tty: true
                environment:
                  SPRING_PROFILES_ACTIVE: prod
                  VANCE_MONGODB_URI: mongodb://${MONGO_INITDB_ROOT_USERNAME:-root}:${MONGO_INITDB_ROOT_PASSWORD:-example}@mongodb:27017/${VANCE_MONGODB_DATABASE:-vance}?authSource=admin
                  VANCE_MONGODB_DATABASE: ${VANCE_MONGODB_DATABASE:-vance}
                  VANCE_ENCRYPTION_PASSWORD: ${VANCE_ENCRYPTION_PASSWORD:-changeit}
                  # Admin commands that touch the brain (e.g. project-kits import)
                  # call it over the compose network — not localhost.
                  VANCE_ANUS_BRAIN_HTTPBASE: http://brain:9990
                  VANCE_ANUS_PASSWORD_HASH: ${VANCE_ANUS_PASSWORD_HASH:-}
                  VANCE_DEFAULT_LANGUAGE: ${VANCE_DEFAULT_LANGUAGE:-English}
                  VANCE_DEFAULT_LANGUAGE_CODE: ${VANCE_DEFAULT_LANGUAGE_CODE:-en}
                volumes:
                  - ./data/anus:/app/data
            """;
}
