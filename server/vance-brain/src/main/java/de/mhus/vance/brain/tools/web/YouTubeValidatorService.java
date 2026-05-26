package de.mhus.vance.brain.tools.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Pre-flight check for YouTube video embeddability. Used by
 * {@code VideoSearchTool} so the LLM never gets handed a video ID
 * that the {@code ```youtube} fence renderer would draw as a
 * "video unavailable" panel.
 *
 * <p>Validation: YouTube oEmbed
 * ({@code GET https://www.youtube.com/oembed?url=&lt;url&gt;&format=json}).
 * A 200 response means the video exists <em>and</em> is embeddable
 * by third parties — exactly the precondition the inline fence
 * needs. 401 means the uploader disabled embedding or the video is
 * private; 404 means the ID is dead. We treat anything non-200 as
 * "not embeddable", drop the result.
 *
 * <p>Cache: in-memory {@link ConcurrentHashMap} with a 24-hour TTL
 * per video id. YouTube embed flags rarely flip; popular videos
 * also recur across multiple searches in a session, so the cache
 * stays warm. A pod restart drops the cache and we revalidate —
 * acceptable, since the oEmbed call is fast and not rate-limited
 * for normal volumes.
 *
 * <p>URL parsing is forgiving: accepts {@code youtu.be/<id>},
 * {@code youtube.com/watch?v=<id>}, {@code /embed/<id>},
 * {@code /shorts/<id>}, {@code /v/<id>}, and bare 11-char IDs.
 * Mirrors what the {@code ```youtube} fence renderer accepts so
 * search and embed agree on what's a valid YouTube reference.
 */
@Service
@Slf4j
public class YouTubeValidatorService {

    private static final String OEMBED_URL =
            "https://www.youtube.com/oembed?url=%s&format=json";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    /** Hard ceiling on the cache so a long-running pod can't leak memory. */
    private static final int CACHE_MAX_ENTRIES = 5000;

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private final ConcurrentMap<String, CachedVerdict> cache = new ConcurrentHashMap<>();
    private final YouTubeOEmbedHttp http;

    @Autowired
    public YouTubeValidatorService() {
        this(new JdkYouTubeOEmbedHttp());
    }

    /** Test-seam constructor — unit tests inject a stubbed probe. */
    YouTubeValidatorService(YouTubeOEmbedHttp http) {
        this.http = http;
    }

    /**
     * Returns the canonical 11-char YouTube video ID for the given
     * URL or bare identifier — or {@code null} when the input
     * doesn't reference a recognisable YouTube video.
     */
    public static @Nullable String extractVideoId(@Nullable String urlOrId) {
        if (urlOrId == null) return null;
        String trimmed = urlOrId.trim();
        if (trimmed.isEmpty()) return null;

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return validId(trimmed);
        }

        String host = uri.getHost();
        if (host == null) {
            // No host — treat the whole input as a candidate bare ID.
            return validId(trimmed);
        }
        host = host.toLowerCase();
        boolean isYouTube = host.equals("youtu.be")
                || host.equals("youtube.com")
                || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com")
                || host.endsWith(".youtube-nocookie.com");
        if (!isYouTube) return null;

        if (host.equals("youtu.be")) {
            String path = uri.getPath();
            if (path != null && path.length() > 1) {
                return validId(firstSegment(path.substring(1)));
            }
            return null;
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        // /watch?v=<id>
        if (path.equals("/watch") || path.endsWith("/watch")) {
            String query = uri.getQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("v=")) {
                        return validId(part.substring(2));
                    }
                }
            }
        }
        // /embed/<id>, /shorts/<id>, /v/<id>, /live/<id>
        for (String prefix : new String[] {"/embed/", "/shorts/", "/v/", "/live/"}) {
            if (path.startsWith(prefix)) {
                return validId(firstSegment(path.substring(prefix.length())));
            }
        }
        return null;
    }

    /**
     * {@code true} when the video is reachable AND embeddable.
     * Wrong/null id, dead video, embed disabled, or oEmbed itself
     * unreachable → {@code false}. Result is cached for
     * {@link #CACHE_TTL_MS}.
     */
    public boolean isEmbeddable(@Nullable String videoId) {
        if (videoId == null || videoId.isBlank()) return false;
        long now = System.currentTimeMillis();
        CachedVerdict cached = cache.get(videoId);
        if (cached != null && cached.expiresAt > now) {
            return cached.ok;
        }
        boolean ok = probe(videoId);
        if (cache.size() < CACHE_MAX_ENTRIES || cached != null) {
            cache.put(videoId, new CachedVerdict(ok, now + CACHE_TTL_MS));
        }
        return ok;
    }

    private boolean probe(String videoId) {
        try {
            String watchUrl = "https://www.youtube.com/watch?v="
                    + URLEncoder.encode(videoId, StandardCharsets.UTF_8);
            URI oembed = URI.create(String.format(OEMBED_URL,
                    URLEncoder.encode(watchUrl, StandardCharsets.UTF_8)));
            int status = http.statusCode(oembed);
            return status == 200;
        } catch (Exception e) {
            log.debug("YouTubeValidator: probe failed for id='{}': {}",
                    videoId, e.toString());
            return false;
        }
    }

    private static @Nullable String validId(@Nullable String candidate) {
        if (candidate == null) return null;
        String s = candidate.trim();
        if (s.length() != 11) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return null;
        }
        return s;
    }

    private static String firstSegment(String s) {
        int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }

    private record CachedVerdict(boolean ok, long expiresAt) {}

    /** Test-seam: wraps the oEmbed status probe behind a stubbable interface. */
    interface YouTubeOEmbedHttp {
        int statusCode(URI oembedUri) throws Exception;
    }

    static final class JdkYouTubeOEmbedHttp implements YouTubeOEmbedHttp {

        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        @Override
        public int statusCode(URI oembedUri) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(oembedUri)
                    .GET()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<Void> response = http.send(
                    request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        }
    }
}
