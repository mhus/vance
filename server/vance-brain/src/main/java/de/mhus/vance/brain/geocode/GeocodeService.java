package de.mhus.vance.brain.geocode;

import de.mhus.vance.api.geocode.GeocodeResult;
import de.mhus.vance.shared.metric.MetricService;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves free-form place names to WGS84 coordinates via the public
 * Nominatim service. In-memory cache keyed by the normalised query
 * string — geocoding results don't drift in practice, and the cache
 * is per-brain-instance so it survives across users in the same pod.
 *
 * <p>OSM Nominatim usage policy compliance:
 * <ul>
 *   <li>Dedicated User-Agent identifying Vance + the instance contact
 *       URL ({@code vance.geocode.contact}).</li>
 *   <li>One request per unique place; subsequent lookups hit the
 *       cache.</li>
 *   <li>Read timeout 10s; failures are not cached so transient
 *       upstream issues self-heal.</li>
 * </ul>
 *
 * <p>Mongo-backed persistence is intentionally not implemented in v1
 * — most brain instances see at most a few dozen unique places, and
 * a fresh pod re-warming from Nominatim is well within policy. The
 * in-memory cache is a bounded LRU ({@link #CACHE_MAX} entries) so a
 * flood of distinct queries can't grow it without limit; if a deployment
 * ever shows cache pressure, swapping it for a Mongo-backed adapter is a
 * local change.
 */
@Service
@Slf4j
public class GeocodeService {

    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final String METRIC_LOOKUPS = "vance.geocode.lookups";
    private static final String METRIC_UPSTREAM = "vance.geocode.upstream";

    /** Upper bound on cached geocode results — oldest (LRU) evicted past this. */
    private static final int CACHE_MAX = 2048;

    private final RestClient restClient;
    // Bounded LRU (access-order + removeEldestEntry), synchronized so the
    // get()-reorders and put()-evictions are thread-safe.
    private final Map<String, GeocodeResult> cache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, GeocodeResult> eldest) {
                    return size() > CACHE_MAX;
                }
            });
    private final MetricService metricService;
    private final String userAgent;

    public GeocodeService(
            MetricService metricService,
            @Value("${vance.geocode.contact:contact@vance.local}") String contactInfo,
            @Value("${vance.build.version:dev}") String buildVersion) {
        this.metricService = metricService;
        this.userAgent = "Vance/" + buildVersion + " (+" + contactInfo + ")";

        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Resolve {@code query} to coordinates. Empty / blank inputs
     * return empty; unresolved names also return empty (never throws
     * — the caller surfaces unresolved entries with a warning in the
     * UI, not as an error condition).
     */
    public Optional<GeocodeResult> lookup(String query) {
        Counter lookups = metricService.counter(METRIC_LOOKUPS,
                "outcome", "hit");
        if (query == null || query.isBlank()) {
            metricService.counter(METRIC_LOOKUPS, "outcome", "empty").increment();
            return Optional.empty();
        }
        String key = normalise(query);
        GeocodeResult cached = cache.get(key);
        if (cached != null) {
            lookups.increment();
            return Optional.of(cached);
        }
        Optional<GeocodeResult> fresh = callUpstream(query);
        fresh.ifPresent(r -> cache.put(key, r));
        metricService.counter(METRIC_LOOKUPS,
                "outcome", fresh.isPresent() ? "miss_resolved" : "miss_unresolved")
                .increment();
        return fresh;
    }

    private Optional<GeocodeResult> callUpstream(String query) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = restClient.get()
                    .uri(NOMINATIM_URL + "?format=json&limit=1&q={q}", query)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        metricService.counter(METRIC_UPSTREAM,
                                "outcome", "http_" + resp.getStatusCode().value())
                                .increment();
                        throw new RestClientException(
                                "Nominatim returned status " + resp.getStatusCode());
                    })
                    .body(List.class);

            if (body == null || body.isEmpty()) {
                metricService.counter(METRIC_UPSTREAM, "outcome", "no_match").increment();
                return Optional.empty();
            }
            Map<String, Object> first = body.get(0);
            Double lat = parseDouble(first.get("lat"));
            Double lon = parseDouble(first.get("lon"));
            String displayName = stringOrEmpty(first.get("display_name"));
            if (lat == null || lon == null) {
                metricService.counter(METRIC_UPSTREAM, "outcome", "malformed").increment();
                return Optional.empty();
            }
            metricService.counter(METRIC_UPSTREAM, "outcome", "resolved").increment();
            return Optional.of(GeocodeResult.builder()
                    .lat(lat)
                    .lon(lon)
                    .displayName(displayName.isEmpty() ? query : displayName)
                    .build());
        } catch (RestClientException e) {
            log.warn("Nominatim lookup failed for '{}': {}", query, e.getMessage());
            metricService.counter(METRIC_UPSTREAM, "outcome", "error").increment();
            return Optional.empty();
        }
    }

    private static String normalise(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static @Nullable Double parseDouble(@Nullable Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stringOrEmpty(@Nullable Object raw) {
        return raw instanceof String s ? s : "";
    }
}
