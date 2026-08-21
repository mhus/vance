package de.mhus.vance.brain.jaglan.protocols;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocolException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A foreign application that embeds {@code vance-ode-jaglan}.
 *
 * <p>Six calls over HTTP, mirroring what that module serves. The one that
 * shapes the class is {@link #open}: its body stays a stream all the way to
 * {@code DocumentService}, because a mount exists precisely so a large file
 * needs no copy on either side.
 *
 * <p><b>404 versus 5xx is not a detail here.</b> A 404 means the source says it
 * does not have the file, and the shell row is dropped; anything else means it
 * could not answer, and the row is kept. The two arrive as
 * {@link Optional#empty()} and a thrown {@link JaglanProtocolException} with
 * {@code refused=false} respectively, and the dispatcher turns the latter into
 * a transient failure. Collapsing them would make a brief outage tell a reader
 * their document does not exist.
 */
@Slf4j
public class OdeJaglanInstance implements JaglanInstance {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Longer than a JSON call: content can be a large file. */
    private static final Duration CONTENT_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration META_TIMEOUT = Duration.ofSeconds(10);

    private final JaglanInstanceConfig cfg;
    private final JaglanHttpClient http;
    private final String base;

    OdeJaglanInstance(JaglanInstanceConfig cfg, JaglanHttpClient http) {
        this.cfg = cfg;
        this.http = http;
        String url = cfg.baseUrl().trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.base = url;
    }

    @Override
    public String mount() {
        return cfg.mount();
    }

    @Override
    public String protocolId() {
        return OdeJaglanProtocol.ID;
    }

    @Override
    public JaglanCapabilities capabilities() {
        JsonNode node = json(get(uri("/capabilities"), META_TIMEOUT), "capabilities");
        MountAccess access = "READ_WRITE".equals(node.path("access").asString(""))
                ? MountAccess.RW
                : MountAccess.RO;
        return new JaglanCapabilities(
                access,
                node.path("canSearch").asBoolean(false),
                node.hasNonNull("itemCount") ? node.get("itemCount").asLong() : null,
                // ISO-8601 on the wire ("PT5M") — self-describing, and the
                // ode side writes it that way. A missing or unparseable value
                // means "no statement", which JaglanCapabilities turns into
                // its default rather than into zero.
                duration(node.path("metadataTtl").asString(null)),
                node.hasNonNull("maxBytes") ? node.get("maxBytes").asLong() : null,
                node.path("displayName").asString(null));
    }

    @Override
    public Optional<MountedStat> stat(String pathInMount) {
        JaglanHttpClient.Response response =
                get(uri("/stat", pathInMount), META_TIMEOUT);
        if (response.statusCode() == 404) {
            // The source answered. Authoritative — not a failure.
            return Optional.empty();
        }
        return Optional.of(toStat(json(response, "stat"), pathInMount));
    }

    @Override
    public List<MountedStat> list(String pathInMount) {
        JsonNode array = json(get(uri("/list", pathInMount), META_TIMEOUT), "list");
        List<MountedStat> out = new ArrayList<>();
        for (JsonNode entry : array) {
            String path = entry.path("path").asString("");
            if (StringUtils.isBlank(path)) {
                // A listing entry without a path cannot be addressed; skipping
                // it beats failing the whole folder for one bad row.
                log.debug("Jaglan ode mount '{}': listing entry without a path, skipped",
                        mount());
                continue;
            }
            out.add(toStat(entry, path));
        }
        return out;
    }

    @Override
    public InputStream open(String pathInMount) {
        URI url = uri("/content", pathInMount);
        JaglanHttpClient.StreamResponse response;
        try {
            response = http.getStream(url, headers(), CONTENT_TIMEOUT);
        } catch (Exception e) {
            throw JaglanProtocolException.unavailable(mount(),
                    "mount '" + mount() + "' content request failed: " + e, e);
        }
        if (!response.isSuccess()) {
            response.close();
            throw failureFor(response.statusCode(), "content", pathInMount);
        }
        // Handed out unread: the caller closes it. This is the one place in the
        // protocol where the body must not be materialised.
        return response.body();
    }

    @Override
    public MountedStat write(String pathInMount, InputStream content) {
        URI url = uri("/content", pathInMount);
        JaglanHttpClient.Response response;
        try {
            response = http.put(url, content, headers(), CONTENT_TIMEOUT);
        } catch (Exception e) {
            throw JaglanProtocolException.unavailable(mount(),
                    "mount '" + mount() + "' write failed: " + e, e);
        }
        if (!response.isSuccess()) {
            throw failureFor(response.statusCode(), "write", pathInMount);
        }
        return toStat(parse(response.body(), "write"), pathInMount);
    }

    @Override
    public void delete(String pathInMount) {
        JaglanHttpClient.Response response;
        try {
            response = http.delete(uri("/content", pathInMount), headers(), META_TIMEOUT);
        } catch (Exception e) {
            throw JaglanProtocolException.unavailable(mount(),
                    "mount '" + mount() + "' delete failed: " + e, e);
        }
        // 404 on a delete is success enough: the caller wanted it gone.
        if (!response.isSuccess() && response.statusCode() != 404) {
            throw failureFor(response.statusCode(), "delete", pathInMount);
        }
    }

    @Override
    public List<MountedStat> search(String query, int limit) {
        URI url = URI.create(base + "/search?q=" + encode(query) + "&limit=" + limit);
        JsonNode array = json(get(url, META_TIMEOUT), "search");
        List<MountedStat> out = new ArrayList<>();
        for (JsonNode entry : array) {
            String path = entry.path("path").asString("");
            if (StringUtils.isNotBlank(path)) out.add(toStat(entry, path));
        }
        return out;
    }

    // ── internals ────────────────────────────────────────────────────

    private URI uri(String suffix) {
        return URI.create(base + suffix);
    }

    private URI uri(String suffix, @Nullable String pathInMount) {
        String path = pathInMount == null ? "" : pathInMount;
        return URI.create(base + suffix + "?path=" + encode(path));
    }

    private static String encode(String raw) {
        // Percent-encoded, not raw: a file name with a space or an ampersand
        // is ordinary, and would otherwise arrive truncated or as two params.
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        String credential = cfg.credentials().get();
        if (StringUtils.isNotBlank(credential)) {
            headers.put("Authorization", "Bearer " + credential);
        }
        return headers;
    }

    private JaglanHttpClient.Response get(URI url, Duration timeout) {
        try {
            return http.get(url, headers(), timeout);
        } catch (Exception e) {
            throw JaglanProtocolException.unavailable(mount(),
                    "mount '" + mount() + "' request to " + url.getPath() + " failed: " + e, e);
        }
    }

    /** Body of a successful response as JSON, or a classified failure. */
    private JsonNode json(JaglanHttpClient.Response response, String op) {
        if (!response.isSuccess()) {
            throw failureFor(response.statusCode(), op, null);
        }
        return parse(response.body(), op);
    }

    private JsonNode parse(@Nullable String body, String op) {
        if (StringUtils.isBlank(body)) {
            throw JaglanProtocolException.unavailable(mount(),
                    "mount '" + mount() + "' returned an empty body for " + op, null);
        }
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            // Malformed JSON is the source being broken, not refusing — and
            // therefore retryable rather than a permanent answer.
            throw JaglanProtocolException.unavailable(mount(),
                    "mount '" + mount() + "' returned unreadable JSON for " + op + ": " + e, e);
        }
    }

    /**
     * Status code → refusal or outage.
     *
     * <p>The line sits at 4xx: a 405 (read-only, what the ode side answers for
     * a write it will not do) and a 403 are stable properties of the source, so
     * the caller should stop asking. Everything else — 5xx, a timeout, a
     * gateway — is worth retrying, and must keep the cached metadata rather
     * than deleting it.
     */
    private JaglanProtocolException failureFor(
            int status, String op, @Nullable String pathInMount) {
        String where = pathInMount == null ? "" : " for '" + pathInMount + "'";
        String message = "mount '" + mount() + "' answered " + status + " on " + op + where;
        if (status == 405 || status == 403 || status == 401 || status == 413) {
            return new JaglanProtocolException(mount(), message);
        }
        return JaglanProtocolException.unavailable(mount(), message, null);
    }

    private MountedStat toStat(JsonNode node, String fallbackPath) {
        String path = node.path("path").asString(fallbackPath);
        boolean folder = node.path("folder").asBoolean(false);
        return new MountedStat(
                path,
                folder,
                node.path("size").asLong(0),
                node.path("mimeType").asString(null),
                node.path("etag").asString(null),
                node.hasNonNull("modifiedAtMs") ? node.get("modifiedAtMs").asLong() : null,
                // Per-entry access is not part of this wire contract: the ode
                // side declares access once, for the whole source. Left UNKNOWN
                // so the shell row takes the mount-level answer instead of a
                // guess made here.
                MountAccess.UNKNOWN,
                node.path("title").asString(null));
    }

    private static Duration duration(@Nullable String iso) {
        if (StringUtils.isBlank(iso)) return JaglanCapabilities.DEFAULT_TTL;
        try {
            return Duration.parse(iso);
        } catch (RuntimeException e) {
            return JaglanCapabilities.DEFAULT_TTL;
        }
    }
}
