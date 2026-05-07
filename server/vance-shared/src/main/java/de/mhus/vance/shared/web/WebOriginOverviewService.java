package de.mhus.vance.shared.web;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Datenhoheit-owner of {@link WebOriginOverviewDocument}. Two
 * responsibilities:
 *
 * <ul>
 *   <li>Read-side: {@link #findByOrigin(String)} for cache hit/miss
 *       checks.</li>
 *   <li>Write-side: {@link #record} upserts a probe outcome with a
 *       caller-chosen TTL — Mongo's TTL index then takes care of
 *       expiry.</li>
 * </ul>
 *
 * <p>Plus an {@link #originOf(URI)} helper that produces the canonical
 * cache key (scheme + host + non-default port). Callers must use it
 * rather than rolling their own — the unique index on
 * {@link WebOriginOverviewDocument#getOrigin()} relies on a single
 * canonical form.
 */
@Service
@Slf4j
public class WebOriginOverviewService {

    private final WebOriginOverviewRepository repository;
    private final Clock clock;

    public WebOriginOverviewService(WebOriginOverviewRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WebOriginOverviewService(WebOriginOverviewRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<WebOriginOverviewDocument> findByOrigin(String origin) {
        return repository.findByOrigin(origin);
    }

    /**
     * Upsert the cache entry for {@code origin}. {@link
     * WebOriginOverviewDocument#getExpireAt()} is set to {@code now +
     * ttl}; passing a zero or negative {@code ttl} is treated as "do
     * not cache" and the existing row (if any) is deleted instead.
     */
    public WebOriginOverviewDocument record(
            String origin,
            OverviewStatus status,
            @Nullable String content,
            int contentLength,
            Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            repository.deleteByOrigin(origin);
            return WebOriginOverviewDocument.builder()
                    .origin(origin)
                    .status(status)
                    .content(content)
                    .contentLength(contentLength)
                    .build();
        }
        Instant now = clock.instant();
        WebOriginOverviewDocument doc = repository.findByOrigin(origin)
                .orElseGet(() -> WebOriginOverviewDocument.builder()
                        .origin(origin)
                        .build());
        doc.setStatus(status);
        doc.setContent(content);
        doc.setContentLength(contentLength);
        doc.setFetchedAt(now);
        doc.setExpireAt(now.plus(ttl));
        return repository.save(doc);
    }

    /** Removes the cache row for {@code origin}, if any. Used by tests / admin. */
    public long delete(String origin) {
        return repository.deleteByOrigin(origin);
    }

    /**
     * Canonical cache key for {@code uri}: {@code scheme://host} with
     * the port appended only when it is non-default for the scheme
     * (i.e. not 80 for http or 443 for https). Returns empty when
     * scheme or host is missing — those cannot be cached and the
     * caller should treat the URL as un-probable.
     */
    public static Optional<String> originOf(URI uri) {
        if (uri == null) return Optional.empty();
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || scheme.isBlank() || host.isBlank()) {
            return Optional.empty();
        }
        scheme = scheme.toLowerCase();
        host = host.toLowerCase();
        int port = uri.getPort();
        boolean defaultPort = port < 0
                || (port == 80 && "http".equals(scheme))
                || (port == 443 && "https".equals(scheme));
        if (defaultPort) {
            return Optional.of(scheme + "://" + host);
        }
        return Optional.of(scheme + "://" + host + ":" + port);
    }
}
