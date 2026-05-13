package de.mhus.vance.foot.connection;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import de.mhus.vance.api.chat.ChatMessageDto;
import de.mhus.vance.api.documents.DocumentDto;
import de.mhus.vance.api.documents.DocumentListResponse;
import de.mhus.vance.api.documents.DocumentUpdateRequest;
import de.mhus.vance.foot.config.FootConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Thin client for the brain's HTTP/REST endpoints, parallel to the
 * WebSocket {@link ConnectionService}. Used for data that the WS
 * protocol intentionally does not expose (chat history, log exports, …)
 * — anything where the request isn't tied to a live session context
 * but to a stable resource lookup.
 *
 * <p>Reuses the JWT from {@link ConnectionService#currentJwt()} so the
 * REST calls authenticate as the same user/tenant as the WebSocket.
 * Mint happens implicitly via {@code connect()}.
 *
 * <p>Future scope: when more REST endpoints land (e.g. project list,
 * settings read/write, file uploads), add typed helpers here rather
 * than scattering raw {@link HttpClient} usage across foot.
 */
@Service
public class BrainRestClientService {

    private final FootConfig config;
    private final ConnectionService connection;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = JsonMapper.builder().build();

    public BrainRestClientService(FootConfig config, ConnectionService connection) {
        this.config = config;
        this.connection = connection;
    }

    /**
     * Fetches recent chat messages for {@code sessionId}. Returns at
     * most {@code limit} entries in chronological order (oldest →
     * newest). Empty list when the session has no chat-process yet.
     *
     * <p>Requires an active connection (so the JWT is available);
     * throws {@link IllegalStateException} otherwise.
     */
    public List<ChatMessageDto> chatHistory(String sessionId, int limit) throws Exception {
        String path = "/brain/" + config.getAuth().getTenant()
                + "/sessions/" + sessionId + "/messages"
                + (limit > 0 ? "?limit=" + limit : "");
        return get(path, new TypeReference<List<ChatMessageDto>>() {});
    }

    /**
     * Authenticated {@code GET} to {@code <httpBase><path>}; JSON
     * response is parsed into the given type reference (supports
     * generic types like {@code List<Foo>}).
     */
    public <T> T get(String path, TypeReference<T> type) throws Exception {
        return get(path, type, (Class<T>) null);
    }

    /** Class-typed convenience over {@link #get(String, TypeReference)}. */
    public <T> T get(String path, Class<T> type) throws Exception {
        return get(path, null, type);
    }

    private <T> T get(String path, @Nullable TypeReference<T> tref, @Nullable Class<T> tcls) throws Exception {
        HttpResponse<String> response = doRequest("GET", path, null, "application/json");
        if (tref != null) return json.readValue(response.body(), tref);
        return json.readValue(response.body(), tcls);
    }

    /**
     * Authenticated {@code GET} returning raw bytes — for binary
     * downloads like document content streams.
     */
    public byte[] getBytes(String path) throws Exception {
        String token = requireToken();
        URI uri = URI.create(config.getBrain().getHttpBase() + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status / 100 != 2) {
            throw new IllegalStateException("REST GET " + path + " failed: HTTP " + status);
        }
        return response.body();
    }

    /** Authenticated {@code PUT} of a JSON body, returning a typed response. */
    public <T> T put(String path, Object body, Class<T> type) throws Exception {
        String json = this.json.writeValueAsString(body);
        HttpResponse<String> response = doRequest("PUT", path, json, "application/json");
        return this.json.readValue(response.body(), type);
    }

    /** Authenticated {@code DELETE}. Returns nothing — throws on non-2xx. */
    public void delete(String path) throws Exception {
        doRequest("DELETE", path, null, null);
    }

    private HttpResponse<String> doRequest(String method, String path,
                                            @Nullable String body,
                                            @Nullable String accept) throws Exception {
        String token = requireToken();
        URI uri = URI.create(config.getBrain().getHttpBase() + path);
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(15));
        if (accept != null) rb.header("Accept", accept);
        if (body != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status / 100 != 2) {
            throw new IllegalStateException("REST " + method + " " + path + " failed: HTTP " + status
                    + (response.body().isEmpty() ? "" : " — " + truncate(response.body(), 400)));
        }
        return response;
    }

    // ─── Documents ────────────────────────────────────────────────

    /** {@code GET /brain/{tenant}/documents?projectId=…} — list. */
    public DocumentListResponse listDocuments(String projectId,
                                              @Nullable String pathPrefix,
                                              @Nullable String kind) throws Exception {
        StringBuilder p = new StringBuilder("/brain/").append(config.getAuth().getTenant())
                .append("/documents?projectId=").append(urlEncode(projectId))
                .append("&size=500");
        if (pathPrefix != null && !pathPrefix.isBlank()) {
            p.append("&pathPrefix=").append(urlEncode(pathPrefix));
        }
        if (kind != null && !kind.isBlank()) {
            p.append("&kind=").append(urlEncode(kind));
        }
        return get(p.toString(), DocumentListResponse.class);
    }

    /** {@code GET /brain/{tenant}/documents/{id}} — full doc with inline text. */
    public DocumentDto getDocument(String id) throws Exception {
        String p = "/brain/" + config.getAuth().getTenant() + "/documents/" + urlEncode(id);
        return get(p, DocumentDto.class);
    }

    /** {@code GET /brain/{tenant}/documents/{id}/content?download=true} — raw bytes. */
    public byte[] downloadDocument(String id) throws Exception {
        String p = "/brain/" + config.getAuth().getTenant() + "/documents/"
                + urlEncode(id) + "/content?download=true";
        return getBytes(p);
    }

    /** {@code PUT /brain/{tenant}/documents/{id}} — patch fields (rename, retitle, etc.). */
    public DocumentDto updateDocument(String id, DocumentUpdateRequest req) throws Exception {
        String p = "/brain/" + config.getAuth().getTenant() + "/documents/" + urlEncode(id);
        return put(p, req, DocumentDto.class);
    }

    /** {@code DELETE /brain/{tenant}/documents/{id}} — soft-delete (or hard, if in trash). */
    public void deleteDocument(String id) throws Exception {
        String p = "/brain/" + config.getAuth().getTenant() + "/documents/" + urlEncode(id);
        delete(p);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String requireToken() {
        @Nullable String t = connection.currentJwt();
        if (t == null || t.isBlank()) {
            throw new IllegalStateException(
                    "Not connected — REST calls need an active JWT. Run /connect first.");
        }
        return t;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
