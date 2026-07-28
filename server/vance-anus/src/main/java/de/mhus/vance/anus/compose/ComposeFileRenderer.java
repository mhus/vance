package de.mhus.vance.anus.compose;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders {@code docker-compose.yml}, the managed {@code .env} key set, and the
 * one-shot {@code setup.sh} from a {@link ComposeSetupState}.
 *
 * <p>The compose file is assembled from typed service fragments rather than a
 * templating engine: {@code mongodb}/{@code brain}/{@code face} are always
 * present, {@code redis} joins when live features are on, a {@code caddy} front
 * joins in external mode with TLS, and the debug UIs (mongo-express +
 * redis-commander) plus the optional {@code anus} admin service ride along under
 * Compose {@code profiles} so they never start with a plain {@code docker compose
 * up}. Concrete values are supplied by {@code .env} via {@code ${VAR}}
 * interpolation — the fragments only decide <b>which</b> services exist and
 * <b>whether</b> a host port is published, not their literal settings.
 *
 * <p>Host ports for {@code mongodb}/{@code redis}/{@code brain} are published
 * only when the operator opts in (expert mode). The default topology binds a
 * single port — the Vance/face port — because the {@code face} image's own nginx
 * already reverse-proxies {@code /brain/*} (REST + WebSocket) to the brain, so
 * the browser reaches everything through one origin.
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

    /** Bare host (no scheme, no path) for Caddy's auto-HTTPS {@code --from}. */
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

    /** External mode with a bundled Caddy that terminates TLS. */
    private static boolean caddyFronts(ComposeSetupState s) {
        return s.isExternalAccess() && s.isCaddyTls();
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
        env.put("VANCE_REDIS_URI", "redis://redis:6379");
        env.put("REDIS_PORT", Integer.toString(s.getRedisPort()));

        env.put("FACE_PORT", Integer.toString(s.getFacePort()));

        // Access mode + browser-facing URL (Brain callbacks / cookie security).
        env.put("VANCE_ACCESS_MODE", s.isExternalAccess() ? "external" : "local");
        env.put("VANCE_WEB_PUBLICBASEURL", publicBaseUrl(s));
        env.put("VANCE_WEB_COOKIES_SECURE", Boolean.toString(cookiesSecure(s)));
        if (s.isExternalAccess()) {
            env.put("VANCE_EXTERNAL_URL", stripTrailingSlash(s.getExternalUrl().strip()));
            env.put("VANCE_CADDY_TLS", s.isCaddyTls() ? "auto" : "off");
            if (caddyFronts(s)) {
                env.put("VANCE_EXTERNAL_HOST", externalHost(s));
            }
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
        sb.append(HEADER);

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
        // In external mode with a bundled TLS Caddy the face stays internal —
        // Caddy is the only published front door. Otherwise the face port is
        // the single Vance port (local, or external HTTP-only behind ngrok).
        if (!caddyFronts(s)) {
            sb.append(FACE_PORTS);
        }

        if (caddyFronts(s)) {
            sb.append(CADDY);
        }

        if (s.isToolsEnabled()) {
            sb.append(MONGO_EXPRESS);
            if (s.isRedisEnabled()) {
                sb.append(REDIS_COMMANDER);
            }
        }
        if (s.isAnusServiceEnabled()) {
            sb.append(ANUS);
        }
        sb.append(volumes(s));
        return sb.toString();
    }

    private static String volumes(ComposeSetupState s) {
        StringBuilder v = new StringBuilder("\nvolumes:\n");
        v.append("  mongo-data:\n");
        v.append("  brain-data:\n");
        v.append("  brain-logs:\n");
        if (s.isRedisEnabled()) {
            v.append("  redis-data:\n");
        }
        if (caddyFronts(s)) {
            v.append("  caddy-data:\n");
            v.append("  caddy-config:\n");
        }
        if (s.isAnusServiceEnabled()) {
            v.append("  anus-data:\n");
        }
        return v.toString();
    }

    // ──────────────────────── setup.sh ────────────────────────

    /**
     * One-shot first-time-setup script. Mirrors the hand-maintained
     * {@code vance-startup/minimal/setup.sh}: it joins the compose network,
     * feeds the same secrets the brain uses, and runs {@code anus --setup} in a
     * throwaway container — so anus never has to be a running service.
     */
    static String renderSetupScript() {
        return SETUP_SH;
    }

    // ──────────────────────── fragments ────────────────────────

    private static final String HEADER = """
            # Generated by `vance-anus --setup-docker-compose`.
            #
            # Quick start:
            #   docker compose up -d
            #   ./setup.sh              # first-time tenant + user + LLM (one-shot anus)
            #   open http://localhost:8080
            #
            # Config lives in the sibling .env — edit it or re-run the wizard.

            name: vance

            services:
            """;

    private static final String MONGODB = """

              mongodb:
                image: mongo:7.0
                restart: unless-stopped
                environment:
                  MONGO_INITDB_ROOT_USERNAME: ${MONGO_INITDB_ROOT_USERNAME:-root}
                  MONGO_INITDB_ROOT_PASSWORD: ${MONGO_INITDB_ROOT_PASSWORD:-example}
                  MONGO_INITDB_DATABASE: ${VANCE_MONGODB_DATABASE:-vance}
                volumes:
                  - mongo-data:/data/db
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
                  - redis-data:/data
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
                  VANCE_WEB_PUBLICBASEURL: ${VANCE_WEB_PUBLICBASEURL:-http://localhost:8080}
                  VANCE_WEB_COOKIES_SECURE: ${VANCE_WEB_COOKIES_SECURE:-false}
            """;

    private static final String BRAIN_TAIL = """
                  JAVA_OPTS: ${BRAIN_JAVA_OPTS:--XX:+UseG1GC -XX:MaxRAMPercentage=75.0}
                volumes:
                  - brain-data:/app/data
                  - brain-logs:/app/logs
            """;

    private static final String BRAIN_PORTS = """
                ports:
                  - "${BRAIN_PORT:-9990}:9990"
            """;

    private static final String BRAIN_NO_REDIS = """

              brain:
                image: ${VANCE_IMAGE_NAMESPACE:-mhus}/vance-brain:${IMAGE_TAG:-latest}
                restart: unless-stopped
                depends_on:
                  mongodb:
                    condition: service_healthy
                environment:
            """ + BRAIN_ENV_COMMON + BRAIN_TAIL;

    private static final String BRAIN_WITH_REDIS = """

              brain:
                image: ${VANCE_IMAGE_NAMESPACE:-mhus}/vance-brain:${IMAGE_TAG:-latest}
                restart: unless-stopped
                depends_on:
                  mongodb:
                    condition: service_healthy
                  redis:
                    condition: service_healthy
                environment:
            """ + BRAIN_ENV_COMMON + """
                  VANCE_REDIS_ENABLED: "true"
                  VANCE_REDIS_URI: ${VANCE_REDIS_URI:-redis://redis:6379}
            """ + BRAIN_TAIL;

    private static final String FACE = """

              face:
                image: ${VANCE_IMAGE_NAMESPACE:-mhus}/vance-face:${IMAGE_TAG:-latest}
                restart: unless-stopped
                depends_on:
                  brain:
                    condition: service_started
            """;

    private static final String FACE_PORTS = """
                ports:
                  - "${FACE_PORT:-8080}:80"
            """;

    private static final String CADDY = """

              # TLS-terminating front door for external access. Auto-provisions a
              # Let's Encrypt certificate for ${VANCE_EXTERNAL_HOST} and reverse-
              # proxies everything to the face (which itself proxies /brain/*).
              # Requires ports 80 + 443 reachable from the public internet.
              caddy:
                image: caddy:2
                restart: unless-stopped
                depends_on:
                  face:
                    condition: service_started
                command: caddy reverse-proxy --from ${VANCE_EXTERNAL_HOST} --to face:80
                ports:
                  - "80:80"
                  - "443:443"
                volumes:
                  - caddy-data:/data
                  - caddy-config:/config
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
              # setup runs as a one-shot container via ./setup.sh. Invoke on
              # demand for the interactive admin REPL:
              #   docker compose run --rm anus
              anus:
                image: ${VANCE_IMAGE_NAMESPACE:-mhus}/vance-anus:${IMAGE_TAG:-latest}
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
                  VANCE_ANUS_PASSWORD_HASH: ${VANCE_ANUS_PASSWORD_HASH:-}
                  VANCE_DEFAULT_LANGUAGE: ${VANCE_DEFAULT_LANGUAGE:-English}
                  VANCE_DEFAULT_LANGUAGE_CODE: ${VANCE_DEFAULT_LANGUAGE_CODE:-en}
                volumes:
                  - anus-data:/app/data
            """;

    private static final String SETUP_SH = """
            #!/usr/bin/env bash
            #
            # Generated by `vance-anus --setup-docker-compose`.
            #
            # One-shot first-time setup: runs the anus `--setup` wizard against
            # the compose-managed MongoDB in a throwaway container, so anus never
            # has to run as a permanent service.
            #
            # Usage:
            #   ./setup.sh              # interactive first-time setup wizard
            #   ./setup.sh --sudo "…"   # any other anus command, same one-shot pattern
            #
            # Requires `docker compose up -d` to have run (network + mongodb exist)
            # and a populated .env next to this script.

            set -euo pipefail
            cd "$(dirname "$0")"

            if [ ! -f .env ]; then
                echo "✗ .env missing — re-run the wizard or restore your .env." >&2
                exit 1
            fi

            # shellcheck disable=SC1091
            set -a; source .env; set +a

            NETWORK="${COMPOSE_PROJECT_NAME:-vance}_default"
            if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
                echo "✗ Docker network '$NETWORK' not found." >&2
                echo "  Start the stack first:  docker compose up -d" >&2
                exit 1
            fi

            MONGO_URI="mongodb://${MONGO_INITDB_ROOT_USERNAME:-root}:${MONGO_INITDB_ROOT_PASSWORD:-example}@mongodb:27017/${VANCE_MONGODB_DATABASE:-vance}?authSource=admin"
            IMAGE="${VANCE_IMAGE_NAMESPACE:-mhus}/vance-anus:${IMAGE_TAG:-latest}"

            ARGS=("$@")
            if [ ${#ARGS[@]} -eq 0 ]; then
                ARGS=(--setup)
            fi

            exec docker run --rm -it \\
                --network "$NETWORK" \\
                -e SPRING_PROFILES_ACTIVE=prod \\
                -e VANCE_MONGODB_URI="$MONGO_URI" \\
                -e VANCE_MONGODB_DATABASE="${VANCE_MONGODB_DATABASE:-vance}" \\
                -e VANCE_ENCRYPTION_PASSWORD="${VANCE_ENCRYPTION_PASSWORD:-changeit}" \\
                -e VANCE_DEFAULT_LANGUAGE="${VANCE_DEFAULT_LANGUAGE:-English}" \\
                -e VANCE_DEFAULT_LANGUAGE_CODE="${VANCE_DEFAULT_LANGUAGE_CODE:-en}" \\
                "$IMAGE" \\
                "${ARGS[@]}"
            """;
}
