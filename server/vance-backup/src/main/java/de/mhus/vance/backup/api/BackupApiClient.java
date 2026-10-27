package de.mhus.vance.backup.api;

import de.mhus.vance.api.backup.BackupEntriesRequest;
import de.mhus.vance.api.backup.BackupEntriesResponse;
import de.mhus.vance.api.backup.BackupInfoResponse;
import de.mhus.vance.api.backup.BackupProjectsRequest;
import de.mhus.vance.api.backup.BackupProjectsResponse;
import de.mhus.vance.api.backup.BackupRunsRequest;
import de.mhus.vance.api.backup.BackupRunsResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for the five {@code /brain/{tenant}/backup/...}
 * surfaces from {@code planning/user-backup.md} §6.1.
 *
 * <p>Backed by the JDK's {@link HttpClient} — no Apache, no OkHttp.
 * That keeps the runtime lean and makes the tool trivially auditable
 * by anyone who knows {@code java.net.http}.
 *
 * <p>Construction takes the three pieces a backup run needs: the
 * brain URL (with scheme), the tenant name, and the signed JWT
 * minted via {@code POST /integration-tokens} with the
 * {@code backup} profile. There is no connection-string mini-language
 * to learn — the values come straight from the config file
 * ({@code planning/user-backup.md} §4.3).
 *
 * <p>Streaming content reads ({@link #downloadContent}) do not pipe
 * the body through a {@code String} — that would balloon the heap
 * for big documents. The caller is responsible for closing the
 * returned {@link InputStream}.
 */
@NullMarked
public final class BackupApiClient implements AutoCloseable {

    /** Connect timeout — same as the brain's default reverse-proxy window. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final URI apiBase;
    private final String tenant;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper json;

    /**
     * Production constructor. The {@code brainUrl} is the root of the
     * brain deployment (no path) and carries its scheme — so a dev
     * brain on {@code http://localhost:9991} and a production brain
     * on {@code https://brain.example} both work without any
     * heuristics.
     */
    public BackupApiClient(URI brainUrl, String tenant, String token) {
        this(
                brainUrl,
                tenant,
                token,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    /** Test-friendly constructor that injects the HTTP client. */
    BackupApiClient(URI brainUrl, String tenant, String token, HttpClient http) {
        if (brainUrl.getScheme() == null
                || (!brainUrl.getScheme().equals("http")
                        && !brainUrl.getScheme().equals("https"))) {
            throw new IllegalArgumentException("brainUrl must use http:// or https:// (got: " + brainUrl + ")");
        }
        if (brainUrl.getHost() == null || brainUrl.getHost().isBlank()) {
            throw new IllegalArgumentException("brainUrl is missing a host: " + brainUrl);
        }
        String path = brainUrl.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("brainUrl must not include a path (got: " + brainUrl
                    + "). Backup routes are appended as /brain/<tenant>/backup.");
        }
        // The API root is <brainUrl>/brain/<tenant>/backup. We resolve
        // against brainUrl so trailing slashes are tolerated.
        this.apiBase = brainUrl.resolve("/brain/" + tenant + "/backup");
        this.tenant = tenant;
        this.token = token;
        this.http = http;
        this.json = new ObjectMapper();
    }

    /** Returns the tenant name passed at construction. */
    public String tenant() {
        return tenant;
    }

    /** Returns the resolved API base URL — exposed for tests and debug output. */
    public URI apiBase() {
        return apiBase;
    }

    /** Sends {@code GET /backup/info}. */
    public BackupInfoResponse getInfo() {
        return parseJson(send("GET", "/info", null).body(), BackupInfoResponse.class);
    }

    /** Sends {@code POST /backup/projects}. */
    public BackupProjectsResponse listProjects(BackupProjectsRequest req) {
        return parseJson(send("POST", "/projects", toJson(req)).body(), BackupProjectsResponse.class);
    }

    /**
     * Sends {@code POST /backup/projects/{project}/entries}. The
     * project name is encoded into the path; the request body carries
     * the deny list and the cursor.
     */
    public BackupEntriesResponse listEntries(String project, BackupEntriesRequest req) {
        String encodedProject = urlEncode(project);
        return parseJson(
                send("POST", "/projects/" + encodedProject + "/entries", toJson(req))
                        .body(),
                BackupEntriesResponse.class);
    }

    /**
     * Streams {@code GET /backup/documents/{id}/content}. Honors the
     * {@code If-None-Match} header for ETag-based deduplication — the
     * caller passes the previous {@code contentVersion} and skips
     * the body when the response is {@code 304 Not Modified}.
     *
     * <p>Returns the {@link HttpResponse} rather than just the body so
     * the caller can inspect the ETag header without a second request.
     * The caller is responsible for closing {@link HttpResponse#body()}.
     */
    public HttpResponse<InputStream> downloadContent(String documentId, @Nullable String ifNoneMatch) {
        String encoded = urlEncode(documentId);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + "/documents/" + encoded + "/content"))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + token)
                .GET();
        if (ifNoneMatch != null) {
            builder.header("If-None-Match", "\"" + ifNoneMatch + "\"");
        }
        return sendChecked(builder.build(), BodyHandlers.ofInputStream());
    }

    /**
     * Sends {@code POST /backup/runs} — the run summary that
     * extends the sliding-lifetime token on success
     * ({@code planning/user-backup.md} §7.6).
     */
    public BackupRunsResponse reportRun(BackupRunsRequest req) {
        return parseJson(send("POST", "/runs", toJson(req)).body(), BackupRunsResponse.class);
    }

    // -- internals ------------------------------------------------------------

    private HttpResponse<String> send(String method, String path, @Nullable String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        switch (method) {
            case "GET" -> builder.GET();
            case "POST" ->
                builder.POST(
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            case "DELETE" -> builder.DELETE();
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return sendChecked(builder.build(), BodyHandlers.ofString());
    }

    private <T> HttpResponse<T> sendChecked(HttpRequest req, HttpResponse.BodyHandler<T> handler) {
        try {
            HttpResponse<T> resp = http.send(req, handler);
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                return resp;
            }
            // For string handlers we can surface the response body in
            // the error. Stream handlers can't — the body may be too
            // big to buffer for an exception message.
            String body = bodyAsString(resp);
            throw new ApiException(
                    status,
                    "Server returned " + status + " for " + req.method() + " "
                            + req.uri().getPath(),
                    body);
        } catch (IOException e) {
            throw new ApiException(0, "I/O error calling " + req.uri() + ": " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "Interrupted while calling " + req.uri(), null, e);
        }
    }

    /**
     * Returns the response body when it is a {@link String} (the
     * {@code BodyHandlers.ofString()} case); {@code null} otherwise.
     * The JDK uses anonymous classes for the built-in handlers, so
     * type-checking by class name is brittle; reading the body and
     * checking its runtime type is the stable path.
     */
    private static String bodyAsString(HttpResponse<?> resp) {
        Object body = resp.body();
        return body instanceof String s ? s : null;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new ApiException(0, "Failed to serialize request: " + e.getMessage(), null, e);
        }
    }

    private <T> T parseJson(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (RuntimeException e) {
            throw new ApiException(0, "Failed to parse server response: " + e.getMessage(), body, e);
        }
    }

    private static String urlEncode(String segment) {
        // Path segments may contain slashes (project names with dots,
        // document IDs). java.net.URLEncoder over-encodes for paths;
        // java.net.URI does the right thing but throws on bad input.
        // We percent-encode by hand for the small set of chars that
        // appear in practice — letters, digits, '-', '_', '.', '~',
        // and the actual structural separators.
        StringBuilder out = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '~') {
                out.append(c);
            } else {
                // UTF-8 percent-encoding per RFC 3986.
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%');
                    out.append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        // HttpClient holds a connection pool — closing it is the
        // polite way to release sockets. JDK 21+ provides close() on
        // HttpClient; on older runtimes we no-op.
        try {
            http.close();
        } catch (RuntimeException ignored) {
            // Some JDK builds don't support HttpClient#close — but
            // vance runs on JDK 25, so this catch is for older test
            // environments only.
        }
    }
}
