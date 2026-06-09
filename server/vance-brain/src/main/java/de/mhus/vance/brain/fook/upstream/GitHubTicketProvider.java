package de.mhus.vance.brain.fook.upstream;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.tenant.TenantService;
import java.net.URI;
import java.net.URLEncoder;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub Issues implementation of {@link TicketProvider}. Talks to
 * the GitHub REST API v3 via {@link HttpClient}; no external GH
 * library dependency.
 *
 * <p>Configuration lives in the {@code _vance} system tenant under
 * the {@code tenant} scope:
 *
 * <ul>
 *   <li>{@code fook.upstream.github.owner} — GitHub org or user</li>
 *   <li>{@code fook.upstream.github.repo} — repo name</li>
 *   <li>{@code fook.upstream.github.token} — PASSWORD-typed PAT or
 *       fine-grained token with {@code issues:write}</li>
 *   <li>{@code fook.upstream.github.apiBase} — defaults to
 *       {@code https://api.github.com}, override for GitHub
 *       Enterprise Server</li>
 *   <li>{@code fook.upstream.github.extraLabels} — comma-separated
 *       list of extra labels to apply to every Fook-created issue</li>
 * </ul>
 *
 * <p><b>Label convention (Vance-flavoured GitHub Issues):</b>
 *
 * <ul>
 *   <li>{@code fook} — every Fook-created issue carries this</li>
 *   <li>{@code fook/<type>} — {@code fook/bug}, {@code fook/feature}, etc.</li>
 *   <li>{@code fook/severity-<level>} — for {@code bug}-type only</li>
 *   <li>… plus any {@code extraLabels} the tenant configured</li>
 * </ul>
 *
 * <p><b>Issue title convention:</b> {@code [fook] <derivedTitle>} —
 * the prefix makes maintainer-side filtering trivial.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubTicketProvider implements TicketProvider {

    public static final String NAME = "github";

    private static final String CFG_OWNER = "fook.upstream.github.owner";
    private static final String CFG_REPO = "fook.upstream.github.repo";
    private static final String CFG_TOKEN = "fook.upstream.github.token";
    private static final String CFG_API_BASE = "fook.upstream.github.apiBase";
    private static final String CFG_EXTRA_LABELS = "fook.upstream.github.extraLabels";

    private static final String DEFAULT_API_BASE = "https://api.github.com";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = "Vance-Fook/1.0";

    private final SettingService settingService;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String name() {
        return NAME;
    }

    // ─── create ─────────────────────────────────────────────────────

    @Override
    public ProviderTicketRef create(ProviderTicketDraft draft) {
        GitHubConfig cfg = loadConfig();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "[fook] " + draft.getTitle());
        body.put("body", draft.getBody());
        body.put("labels", labelsFor(draft, cfg));

        String json = serialize(body);
        HttpResponse<String> resp = sendJson(
                "POST",
                cfg.apiBase + "/repos/" + cfg.owner + "/" + cfg.repo + "/issues",
                json, cfg);

        if (resp.statusCode() / 100 != 2) {
            throw retryableOrPermanent(
                    "GitHub create issue", resp);
        }
        JsonNode node = parse(resp.body());
        String number = node.path("number").asString();
        String url = node.path("html_url").asString();
        return ProviderTicketRef.builder()
                .provider(NAME)
                .externalId(number)
                .url(url)
                .build();
    }

    // ─── postComment ────────────────────────────────────────────────

    @Override
    public void postComment(ProviderTicketRef ref, String body) {
        GitHubConfig cfg = loadConfig();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        String json = serialize(payload);
        HttpResponse<String> resp = sendJson(
                "POST",
                cfg.apiBase + "/repos/" + cfg.owner + "/" + cfg.repo
                        + "/issues/" + ref.getExternalId() + "/comments",
                json, cfg);
        if (resp.statusCode() / 100 != 2) {
            throw retryableOrPermanent("GitHub post comment", resp);
        }
    }

    // ─── pollUpdates ────────────────────────────────────────────────

    @Override
    public List<ProviderTicketUpdate> pollUpdates(
            List<ProviderTicketRef> tracked, Instant since) {
        if (tracked.isEmpty()) return List.of();
        GitHubConfig cfg = loadConfig();

        // GH's /issues endpoint supports ?since=<ISO> — server-side
        // filter that returns issues+PRs touched since that instant.
        // We also filter by label=fook to avoid bringing in irrelevant
        // ones. v1 simplification: we pull the union list and then
        // filter to only those in our `tracked` set; the GH API
        // doesn't accept an "ids=" multi-select on /issues so this
        // is the cheap default.
        String url = cfg.apiBase + "/repos/" + cfg.owner + "/" + cfg.repo
                + "/issues?since=" + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8)
                + "&labels=fook&state=all&per_page=100";
        HttpResponse<String> resp = sendJson("GET", url, null, cfg);
        if (resp.statusCode() / 100 != 2) {
            throw retryableOrPermanent("GitHub poll issues", resp);
        }
        JsonNode arr = parse(resp.body());
        if (!arr.isArray()) return List.of();

        Map<String, ProviderTicketRef> byId = new LinkedHashMap<>();
        for (ProviderTicketRef r : tracked) byId.put(r.getExternalId(), r);

        List<ProviderTicketUpdate> out = new ArrayList<>();
        for (JsonNode node : arr) {
            String num = node.path("number").asString();
            ProviderTicketRef ref = byId.get(num);
            if (ref == null) continue;
            String state = node.path("state").asString();
            Instant updatedAt = parseInstant(node.path("updated_at").asString());

            List<ProviderTicketUpdate.ProviderComment> newComments =
                    fetchCommentsSince(cfg, num, since);

            // Skip emitting an update if neither state nor comments
            // changed since the previous tick. State updates are
            // detected by FookUpstreamService comparing to the
            // currently-stored upstreamState; newComments==empty +
            // updated_at <= since is a no-op for our purposes.
            if (newComments.isEmpty() && updatedAt != null && !updatedAt.isAfter(since)) {
                continue;
            }
            out.add(ProviderTicketUpdate.builder()
                    .ref(ref)
                    .state(state)
                    .updatedAt(updatedAt)
                    .newComments(newComments)
                    .build());
        }
        return out;
    }

    private List<ProviderTicketUpdate.ProviderComment> fetchCommentsSince(
            GitHubConfig cfg, String issueNumber, Instant since) {
        String url = cfg.apiBase + "/repos/" + cfg.owner + "/" + cfg.repo
                + "/issues/" + issueNumber + "/comments?since="
                + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8)
                + "&per_page=50";
        HttpResponse<String> resp = sendJson("GET", url, null, cfg);
        if (resp.statusCode() / 100 != 2) {
            log.warn("Fook: GH fetch-comments failed for #{}: HTTP {}",
                    issueNumber, resp.statusCode());
            return List.of();
        }
        JsonNode arr = parse(resp.body());
        if (!arr.isArray()) return List.of();
        List<ProviderTicketUpdate.ProviderComment> out = new ArrayList<>();
        for (JsonNode c : arr) {
            out.add(ProviderTicketUpdate.ProviderComment.builder()
                    .externalId(c.path("id").asString())
                    .author(c.path("user").path("login").asString())
                    .body(c.path("body").asString())
                    .createdAt(parseInstant(c.path("created_at").asString()))
                    .build());
        }
        return out;
    }

    // ─── checkConnection ────────────────────────────────────────────

    @Override
    public HealthCheckResult checkConnection() {
        GitHubConfig cfg;
        try {
            cfg = loadConfig();
        } catch (ProviderException e) {
            return HealthCheckResult.builder()
                    .ok(false)
                    .message("Configuration incomplete: " + e.getMessage())
                    .build();
        }
        try {
            HttpResponse<String> who = sendJson(
                    "GET", cfg.apiBase + "/user", null, cfg);
            if (who.statusCode() / 100 != 2) {
                return HealthCheckResult.builder()
                        .ok(false)
                        .message("Auth failed: HTTP " + who.statusCode())
                        .details(snippet(who.body()))
                        .build();
            }
            String login = parse(who.body()).path("login").asString();

            HttpResponse<String> repo = sendJson("GET",
                    cfg.apiBase + "/repos/" + cfg.owner + "/" + cfg.repo,
                    null, cfg);
            if (repo.statusCode() / 100 != 2) {
                return HealthCheckResult.builder()
                        .ok(false)
                        .message("Repo " + cfg.owner + "/" + cfg.repo
                                + " not accessible: HTTP " + repo.statusCode())
                        .details("Authenticated as " + login)
                        .build();
            }
            JsonNode repoNode = parse(repo.body());
            int openIssues = repoNode.path("open_issues_count").asInt();
            return HealthCheckResult.builder()
                    .ok(true)
                    .message("Authenticated as " + login
                            + ", target " + cfg.owner + "/" + cfg.repo
                            + " accessible (" + openIssues + " open issues)")
                    .build();
        } catch (RuntimeException e) {
            return HealthCheckResult.builder()
                    .ok(false)
                    .message("Unexpected failure: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage())
                    .build();
        }
    }

    // ─── helpers ────────────────────────────────────────────────────

    private GitHubConfig loadConfig() {
        // Cascade-read so the setting-form's "tenant" scope writes
        // (which land at SCOPE_PROJECT + _tenant) are picked up.
        String tenant = TenantService.SYSTEM_TENANT;
        String owner = orDefault(settingService.getStringValueCascade(
                tenant, null, null, CFG_OWNER), "");
        String repo = orDefault(settingService.getStringValueCascade(
                tenant, null, null, CFG_REPO), "");
        String token = settingService.getDecryptedPasswordCascade(
                tenant, null, null, CFG_TOKEN);
        String apiBase = orDefault(settingService.getStringValueCascade(
                tenant, null, null, CFG_API_BASE), DEFAULT_API_BASE);
        String extraLabelsRaw = orDefault(settingService.getStringValueCascade(
                tenant, null, null, CFG_EXTRA_LABELS), "");

        if (owner.isBlank() || repo.isBlank() || token == null || token.isBlank()) {
            throw new ProviderException(
                    "GitHub upstream not fully configured (owner / repo / token)", false);
        }
        return new GitHubConfig(owner, repo, token, apiBase, splitLabels(extraLabelsRaw));
    }

    private static String orDefault(String v, String defaultValue) {
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private static List<String> splitLabels(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<String> labelsFor(ProviderTicketDraft draft, GitHubConfig cfg) {
        List<String> labels = new ArrayList<>();
        labels.add("fook");
        if (draft.getType() != null && !draft.getType().isBlank()) {
            labels.add("fook/" + draft.getType());
        }
        if ("bug".equals(draft.getType())
                && draft.getSeverity() != null && !draft.getSeverity().isBlank()) {
            labels.add("fook/severity-" + draft.getSeverity());
        }
        if (draft.getExtraLabels() != null) labels.addAll(draft.getExtraLabels());
        labels.addAll(cfg.extraLabels);
        return labels.stream().distinct().toList();
    }

    private HttpResponse<String> sendJson(
            String method, String url, String jsonBody, GitHubConfig cfg) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + cfg.token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", USER_AGENT);
        if (jsonBody != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (RuntimeException e) {
            throw new ProviderException("GitHub HTTP error: " + e.getMessage(), e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("GitHub HTTP interrupted", e, true);
        } catch (java.io.IOException e) {
            throw new ProviderException("GitHub HTTP I/O error: " + e.getMessage(), e, true);
        }
    }

    private String serialize(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw new ProviderException(
                    "Failed to serialise GitHub request body", e, false);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new ProviderException(
                    "Failed to parse GitHub response body", e, false);
        }
    }

    private ProviderException retryableOrPermanent(
            String op, HttpResponse<String> resp) {
        int sc = resp.statusCode();
        // 4xx (except 429) are permanent — bad credentials, repo gone,
        // malformed payload. 429 + 5xx are transient — backoff next tick.
        boolean retryable = sc == 429 || sc >= 500;
        String msg = op + " failed: HTTP " + sc + " — " + snippet(resp.body());
        return new ProviderException(msg, retryable);
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String trimmed = body.strip();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400) + "…";
    }

    private static java.time.Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record GitHubConfig(
            String owner, String repo, String token, String apiBase,
            List<String> extraLabels) {}
}
