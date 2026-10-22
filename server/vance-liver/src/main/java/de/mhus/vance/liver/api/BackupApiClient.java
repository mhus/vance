package de.mhus.vance.liver.api;

import de.mhus.vance.api.backup.BackupEntriesRequest;
import de.mhus.vance.api.backup.BackupEntriesResponse;
import de.mhus.vance.api.backup.BackupInfoResponse;
import de.mhus.vance.api.backup.BackupProjectsRequest;
import de.mhus.vance.api.backup.BackupProjectsResponse;
import de.mhus.vance.api.backup.BackupRunsRequest;
import de.mhus.vance.api.backup.BackupRunsResponse;
import de.mhus.vance.liver.config.ConfigException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
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
 * <p>Connection string format ({@code planning/user-backup.md} §4.3):
 * {@code vancetope1.<brainHost>/<tenant>/<profile>/<token>}. The
 * profile is fixed to {@code backup} for this tool — the connection
 * string does not carry a per-request profile, it identifies the
 * tool.
 *
 * <p>Streaming content reads ({@link #downloadContent}) do not pipe
 * the body through a {@code String} — that would balloon the heap
 * for big documents. The caller is responsible for closing the
 * returned {@link InputStream}.
 */
@NullMarked
public final class BackupApiClient implements AutoCloseable {

    /** Profile name the connection string must reference. */
    public static final String PROFILE = "backup";

    /** Connect timeout — same as the brain's default reverse-proxy window. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final URI base;
    private final String tenant;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper json;

    /**
     * @param connectionString {@code vancetope1.<host>/<tenant>/backup/<token>}
     */
    public BackupApiClient(String connectionString) {
        this(
                connectionString,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    /** Test-friendly constructor that injects the HTTP client. */
    BackupApiClient(String connectionString, HttpClient http) {
        this(connectionString, http, "https");
    }

    /**
     * Test-only escape hatch: override the URL scheme so a loopback
     * {@code HttpServer} (which speaks plain HTTP) can stand in for
     * the brain. The default scheme is {@code https}; production code
     * always reaches the constructor above.
     */
    BackupApiClient(String connectionString, HttpClient http, String scheme) {
        Parsed parsed = parseConnection(connectionString, scheme);
        this.base = parsed.base;
        this.tenant = parsed.tenant;
        this.token = parsed.token;
        this.http = http;
        this.json = new ObjectMapper();
    }

    /** Parses {@code vancetope1.<host>/<tenant>/<profile>/<token>}. */
    private static Parsed parseConnection(String connection, String scheme) {
        if (!connection.startsWith("vancetope1.")) {
            throw new ConfigException("Connection string must start with 'vancetope1.'");
        }
        String rest = connection.substring("vancetope1.".length());
        String[] parts = rest.split("/", -1);
        if (parts.length != 4) {
            throw new ConfigException(
                    "Connection string must have 4 slash-separated parts: vancetope1.<host>/<tenant>/<profile>/<token>");
        }
        if (!parts[2].equals(PROFILE)) {
            throw new ConfigException("Connection string profile must be '" + PROFILE + "' (got: " + parts[2] + ")");
        }
        URI base;
        try {
            base = URI.create(scheme + "://" + parts[0] + "/brain/" + parts[1] + "/backup");
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid brain host in connection string: " + parts[0], e);
        }
        return new Parsed(base, parts[1], parts[3]);
    }

    private record Parsed(URI base, String tenant, String token) {}

    /**
     * Returns the tenant name parsed from the connection string.
     * Exposed for logging and for the {@code run} command.
     */
    public String tenant() {
        return tenant;
    }

    /**
     * Sends {@code GET /backup/info}.
     */
    public BackupInfoResponse getInfo() {
        HttpResponse<String> resp = send("GET", "/info", null);
        return parseJson(resp.body(), BackupInfoResponse.class);
    }

    /**
     * Sends {@code POST /backup/projects}.
     */
    public BackupProjectsResponse listProjects(BackupProjectsRequest req) {
        HttpResponse<String> resp = send("POST", "/projects", toJson(req));
        return parseJson(resp.body(), BackupProjectsResponse.class);
    }

    /**
     * Sends {@code POST /backup/projects/{project}/entries}. The
     * project name is encoded into the path; the request body carries
     * the deny list and the cursor.
     */
    public BackupEntriesResponse listEntries(String project, BackupEntriesRequest req) {
        String encodedProject = urlEncode(project);
        HttpResponse<String> resp = send("POST", "/projects/" + encodedProject + "/entries", toJson(req));
        return parseJson(resp.body(), BackupEntriesResponse.class);
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/documents/" + encoded + "/content"))
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
        HttpResponse<String> resp = send("POST", "/runs", toJson(req));
        return parseJson(resp.body(), BackupRunsResponse.class);
    }

    /** Returns the base URL — exposed for tests and debug output. */
    public URI baseUrl() {
        return base;
    }

    // -- internals ------------------------------------------------------------

    private HttpResponse<String> send(String method, String path, @Nullable String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
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
            // 401/403 → token problem (exit 2). 410 Gone → token
            // revoked. 404 → endpoint removed mid-version (rare but
            // possible). Everything else is also exit 2.
            String body = "string".equals(handler.getClass().getName()) ? readAsString(resp) : null;
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

    @SuppressWarnings("unchecked")
    private static String readAsString(HttpResponse<?> resp) {
        try {
            // BodyHandlers.ofString returns a String body — this
            // helper narrows it for the error message without
            // forcing the caller to deal with generics.
            Object body = resp.body();
            return body instanceof String s ? s : null;
        } catch (ClassCastException e) {
            return null;
        }
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
                for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
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
