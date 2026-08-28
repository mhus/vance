package de.mhus.vance.brain.fook.upstream;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.tenant.TenantService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reports to the Vancetope collector — the adapter an installation uses
 * when its operator configured nothing.
 *
 * <p><b>Lives in the public brain, not in {@code vance-ee}</b>, and that is
 * load-bearing: this is the default provider, so every installation reports
 * through it, and a community installation has no EE module on its
 * classpath. Only the collector itself is commercial; the sender is not.
 *
 * <p>Configuration is two settings in the {@code _vance} system tenant,
 * and one of them writes itself:
 *
 * <ul>
 *   <li>{@code fook.upstream.vancetope.endpoint} — where the collector is,
 *       defaulting to the public one. Overridable for a private collector
 *       and for tests.</li>
 *   <li>{@code fook.upstream.vancetope.instanceHandle} — PASSWORD-typed,
 *       written by this adapter the first time the collector issues one.
 *       It is what the collector's rate limit is keyed on, which is why it
 *       is issued rather than chosen.</li>
 * </ul>
 *
 * <p><b>Polling is one request per ticket.</b> The collector has no listing
 * and no search — a handle is the only way in, by design — so there is no
 * {@code ?since=} to lean on the way GitHub does. The cost is paid here,
 * with a per-pass cap, rather than pushed into the tick: an installation
 * with two hundred open tickets must not hit the collector two hundred
 * times an hour.
 *
 * <p>Spec: {@code planning/fook-vancetope-connector.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VancetopeTicketProvider implements TicketProvider {

    public static final String NAME = "vancetope";

    static final String CFG_ENDPOINT = "fook.upstream.vancetope.endpoint";
    static final String CFG_INSTANCE_HANDLE = "fook.upstream.vancetope.instanceHandle";

    static final String DEFAULT_ENDPOINT = "https://issues.vancetope.com";

    /** What the collector reads the instance handle from. */
    private static final String INSTANCE_HEADER = "X-Fook-Instance";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private static final String USER_AGENT = "vancetope-fook/1";

    /**
     * How many tickets one poll pass may ask about. Each is a round trip;
     * the rest wait for the next tick, which is hourly and therefore
     * catches up long before anybody notices — the caller hands over the
     * least-recently-asked ones first, so "the rest" is a queue and not a
     * tail nobody reaches.
     */
    static final int POLL_BATCH = 25;

    private final SettingService settingService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supportsPolling() {
        return true;
    }

    @Override
    public int pollBatchSize() {
        return POLL_BATCH;
    }

    // ─── create ─────────────────────────────────────────────────────

    @Override
    public ProviderTicketRef create(ProviderTicketDraft draft) {
        Config cfg = loadConfig();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", draft.getTitle());
        body.put("body", draft.getBody());
        body.put("type", draft.getType());
        body.put("severity", draft.getSeverity());
        body.put("reporterHash", draft.getReporterHash());
        body.put("instanceFingerprint", draft.getInstanceFingerprint());
        body.put("fookTicketId", draft.getFookTicketId());

        HttpResponse<String> resp = send(
                "POST", cfg.endpoint + "/api/report/tickets", serialize(body), cfg);
        if (resp.statusCode() / 100 != 2) {
            throw failure("Vancetope create ticket", resp);
        }

        JsonNode node = parse(resp.body());
        String issued = text(node, "instanceHandle");
        if (issued != null && !issued.isBlank()) {
            // The collector registered us. Persist before anything else can
            // fail: without the handle every later report starts a new
            // instance, and the rate limit loses the addressee it exists for.
            storeInstanceHandle(issued);
        }

        String handle = text(node, "handle");
        String displayId = text(node, "displayId");
        if (handle == null || displayId == null) {
            throw new ProviderException(
                    "Vancetope create ticket: response carried no handle", false);
        }
        return ProviderTicketRef.builder()
                .provider(NAME)
                .externalId(handle)
                .displayId(displayId)
                .url(text(node, "url"))
                .build();
    }

    // ─── postComment ────────────────────────────────────────────────

    @Override
    public void postComment(ProviderTicketRef ref, String body) {
        Config cfg = loadConfig();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("author", "reporter");
        HttpResponse<String> resp = send("POST",
                cfg.endpoint + "/api/report/tickets/" + ref.getExternalId() + "/comments",
                serialize(payload), cfg);
        if (resp.statusCode() / 100 != 2) {
            throw failure("Vancetope post comment", resp);
        }
    }

    // ─── pollUpdates ────────────────────────────────────────────────

    @Override
    public List<ProviderTicketUpdate> pollUpdates(
            List<ProviderTicketRef> tracked, Instant since) {
        if (tracked.isEmpty()) return List.of();
        Config cfg = loadConfig();

        // No slicing here: the caller has already cut the batch to
        // pollBatchSize(), and it is the only side that can order it — see
        // TicketProvider.pollBatchSize.
        List<ProviderTicketUpdate> out = new ArrayList<>();
        for (ProviderTicketRef ref : tracked) {
            HttpResponse<String> resp = send(
                    "GET", cfg.endpoint + "/api/report/tickets/" + ref.getExternalId(),
                    null, cfg);
            if (resp.statusCode() == 404) {
                // The collector forgot this ticket, or the handle stopped
                // being valid. Nothing to mirror, and nothing to retry —
                // skip it rather than fail the whole pass for the others.
                log.info("Fook upstream: collector no longer knows ticket {}", ref.getDisplayId());
                continue;
            }
            if (resp.statusCode() / 100 != 2) {
                throw failure("Vancetope poll ticket " + ref.getDisplayId(), resp);
            }
            ProviderTicketUpdate update = readUpdate(ref, parse(resp.body()), since);
            if (update != null) out.add(update);
        }
        return out;
    }

    /**
     * One ticket's answer, or null when nothing about it is new.
     *
     * <p>The state is always reported: {@code FookUpstreamService} compares
     * it against what it stored, so sending it costs nothing and omitting it
     * would hide a change that happened while comments did not.
     */
    @Nullable ProviderTicketUpdate readUpdate(
            ProviderTicketRef ref, JsonNode node, Instant since) {
        Instant updatedAt = instant(text(node, "updatedAt"));

        List<ProviderTicketUpdate.ProviderComment> fresh = new ArrayList<>();
        JsonNode comments = node.path("comments");
        if (comments.isArray()) {
            for (JsonNode c : comments) {
                Instant createdAt = instant(text(c, "createdAt"));
                if (createdAt != null && !createdAt.isAfter(since)) continue;
                // The reporter's own contributions come back too. Handing
                // them to the inbox would tell somebody about their own
                // message as if it were an answer.
                if ("BRIDGE".equals(text(c, "origin")) || "WEB".equals(text(c, "origin"))) {
                    continue;
                }
                fresh.add(ProviderTicketUpdate.ProviderComment.builder()
                        .externalId(text(c, "id"))
                        .author(orDefault(text(c, "author"), "maintainer"))
                        .body(orDefault(text(c, "body"), ""))
                        .createdAt(createdAt == null ? Instant.now() : createdAt)
                        .build());
            }
        }

        if (fresh.isEmpty() && updatedAt != null && !updatedAt.isAfter(since)) return null;

        return ProviderTicketUpdate.builder()
                .ref(ref)
                .state(lower(text(node, "state")))
                .updatedAt(updatedAt)
                .newComments(fresh)
                .build();
    }

    // ─── health ─────────────────────────────────────────────────────

    @Override
    public HealthCheckResult checkConnection() {
        Config cfg;
        try {
            cfg = loadConfig();
        } catch (ProviderException e) {
            return HealthCheckResult.builder()
                    .ok(false).message("Configuration incomplete: " + e.getMessage()).build();
        }
        try {
            // The collector has no whoami: it has no accounts, which is the
            // whole point. Reachability plus whether we are registered is
            // everything there is to check.
            HttpResponse<String> resp = send(
                    "GET", cfg.endpoint + "/actuator/health", null, cfg);
            if (resp.statusCode() / 100 != 2) {
                return HealthCheckResult.builder()
                        .ok(false)
                        .message("Collector at " + cfg.endpoint
                                + " answered HTTP " + resp.statusCode())
                        .build();
            }
            boolean registered = cfg.instanceHandle != null && !cfg.instanceHandle.isBlank();
            return HealthCheckResult.builder()
                    .ok(true)
                    .message("Collector at " + cfg.endpoint + " reachable"
                            + (registered
                                    ? "; this instance is registered"
                                    : "; not registered yet — the first report registers it"))
                    .build();
        } catch (RuntimeException e) {
            return HealthCheckResult.builder()
                    .ok(false)
                    .message("Unexpected failure: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage())
                    .build();
        }
    }

    // ─── config ─────────────────────────────────────────────────────

    private Config loadConfig() {
        String tenant = TenantService.SYSTEM_TENANT;
        String endpoint = orDefault(settingService.getStringValueCascade(
                tenant, null, null, CFG_ENDPOINT), DEFAULT_ENDPOINT);
        if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        String handle = settingService.getDecryptedPasswordCascade(
                tenant, null, null, CFG_INSTANCE_HANDLE);
        // No completeness check on purpose: unconfigured IS the configured
        // state here. That is the difference from the GitHub adapter, and
        // the reason this one is the default.
        return new Config(endpoint, handle);
    }

    /**
     * Keep the handle the collector issued.
     *
     * <p>PASSWORD-typed: it is what identifies this installation to the
     * collector, so an agent or a script must not be able to read it back
     * and report as somebody else.
     */
    private void storeInstanceHandle(String handle) {
        try {
            settingService.setEncryptedPassword(
                    TenantService.SYSTEM_TENANT, null, null, CFG_INSTANCE_HANDLE, handle);
            log.info("Fook upstream: registered with the Vancetope collector");
        } catch (RuntimeException e) {
            // Not fatal for this ticket — it was accepted. But say it loudly:
            // every later report will register again, and the collector's
            // rate limit will be counting a stream of one-shot instances.
            log.warn("Fook upstream: could not persist the collector's instance handle ({}). "
                    + "Reports will keep registering as new instances.", e.toString());
        }
    }

    // ─── HTTP ───────────────────────────────────────────────────────

    private HttpResponse<String> send(
            String method, String url, @Nullable String jsonBody, Config cfg) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (cfg.instanceHandle != null && !cfg.instanceHandle.isBlank()) {
            rb.header(INSTANCE_HEADER, cfg.instanceHandle);
        }
        if (jsonBody != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Vancetope HTTP interrupted", e, true);
        } catch (java.io.IOException e) {
            throw new ProviderException(
                    "Vancetope HTTP I/O error: " + e.getMessage(), e, true);
        } catch (RuntimeException e) {
            throw new ProviderException("Vancetope HTTP error: " + e.getMessage(), e, true);
        }
    }

    /**
     * Classify a refusal.
     *
     * <p>429 carries the collector's own {@code Retry-After}, which is the
     * number the sender-tick needs to end its pass instead of running every
     * remaining ticket into the same limit. Everything else follows the
     * ordinary rule: 5xx is transient, 4xx is ours to fix.
     */
    private ProviderException failure(String op, HttpResponse<String> resp) {
        int sc = resp.statusCode();
        String message = op + " failed: HTTP " + sc + " — " + snippet(resp.body());
        if (sc == 429) {
            return ProviderException.rateLimited(message, retryAfterOf(resp));
        }
        return new ProviderException(message, sc >= 500);
    }

    private Duration retryAfterOf(HttpResponse<String> resp) {
        return resp.headers().firstValue("Retry-After")
                .map(String::trim)
                .filter(v -> v.matches("\\d+"))
                .map(v -> Duration.ofSeconds(Long.parseLong(v)))
                .orElse(Duration.ofMinutes(1));
    }

    // ─── json helpers ───────────────────────────────────────────────

    private String serialize(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw new ProviderException("Vancetope payload not serialisable", e, false);
        }
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new ProviderException("Vancetope response not parseable", e, false);
        }
    }

    private @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private @Nullable Instant instant(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The collector speaks {@code OPEN}; the rest of Fook stores {@code open}. */
    private @Nullable String lower(@Nullable String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private String orDefault(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String snippet(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    private record Config(String endpoint, @Nullable String instanceHandle) {}
}
