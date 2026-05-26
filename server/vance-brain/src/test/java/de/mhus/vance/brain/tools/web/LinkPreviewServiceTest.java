package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.web.LinkPreviewDto;
import de.mhus.vance.brain.tools.web.LinkPreviewService.PreviewHttp;
import de.mhus.vance.brain.tools.web.LinkPreviewService.PreviewHttpResponse;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.web.LinkPreviewCacheDocument;
import de.mhus.vance.shared.web.LinkPreviewCacheRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LinkPreviewService}. Covers OG-tag
 * extraction, fallback chain (twitter:* → &lt;title&gt; →
 * meta[name=description]), failure shapes (4xx, no metadata,
 * non-HTML), and cache reuse. In-memory cache + stubbed HTTP
 * client — no network involved.
 */
class LinkPreviewServiceTest {

    private static final String TENANT = "acme";

    private InMemoryLinkPreviewCache cache;
    private StubHttp http;
    private SettingService settings;
    private LinkPreviewService service;

    @BeforeEach
    void setUp() {
        cache = new InMemoryLinkPreviewCache();
        http = new StubHttp();
        settings = mock(SettingService.class);
        when(settings.getStringValueCascade(any(), any(), any(), any())).thenReturn(null);
        service = new LinkPreviewService(cache, settings, http);
    }

    @Test
    void preview_fullOgTags_extractsAll() {
        http.response = PreviewHttpResponse.builder()
                .status(200)
                .finalUri(URI.create("https://en.wikipedia.org/wiki/Lisbon"))
                .contentType("text/html; charset=UTF-8")
                .body("""
                        <html><head>
                        <meta property="og:title" content="Lisbon"/>
                        <meta property="og:description" content="Capital of Portugal"/>
                        <meta property="og:image" content="https://en.wikipedia.org/static/lisbon.jpg"/>
                        <meta property="og:site_name" content="Wikipedia"/>
                        <meta property="og:type" content="article"/>
                        <title>Lisbon - Wikipedia</title>
                        </head><body></body></html>
                        """)
                .build();
        LinkPreviewDto dto = service.preview(
                "https://en.wikipedia.org/wiki/Lisbon", TENANT, null, null);
        assertThat(dto.isOk()).isTrue();
        assertThat(dto.getTitle()).isEqualTo("Lisbon");
        assertThat(dto.getDescription()).isEqualTo("Capital of Portugal");
        assertThat(dto.getImage()).isEqualTo("https://en.wikipedia.org/static/lisbon.jpg");
        assertThat(dto.getSiteName()).isEqualTo("Wikipedia");
        assertThat(dto.getType()).isEqualTo("article");
    }

    @Test
    void preview_fallsBackToTwitterTags() {
        http.response = PreviewHttpResponse.builder()
                .status(200)
                .finalUri(URI.create("https://example.com/post"))
                .contentType("text/html")
                .body("""
                        <html><head>
                        <meta name="twitter:title" content="Twitter Title"/>
                        <meta name="twitter:description" content="From the bird site"/>
                        <meta name="twitter:image" content="https://cdn.example.com/img.png"/>
                        </head></html>
                        """)
                .build();
        LinkPreviewDto dto = service.preview(
                "https://example.com/post", TENANT, null, null);
        assertThat(dto.isOk()).isTrue();
        assertThat(dto.getTitle()).isEqualTo("Twitter Title");
        assertThat(dto.getDescription()).isEqualTo("From the bird site");
        assertThat(dto.getImage()).isEqualTo("https://cdn.example.com/img.png");
        // siteName falls back to hostname when neither og:site_name
        // nor twitter:site are present.
        assertThat(dto.getSiteName()).isEqualTo("example.com");
    }

    @Test
    void preview_fallsBackToHtmlTitle() {
        http.response = PreviewHttpResponse.builder()
                .status(200)
                .finalUri(URI.create("https://example.com/x"))
                .contentType("text/html")
                .body("<html><head><title>Plain Page Title</title>"
                        + "<meta name=\"description\" content=\"Plain desc\"/></head></html>")
                .build();
        LinkPreviewDto dto = service.preview(
                "https://example.com/x", TENANT, null, null);
        assertThat(dto.isOk()).isTrue();
        assertThat(dto.getTitle()).isEqualTo("Plain Page Title");
        assertThat(dto.getDescription()).isEqualTo("Plain desc");
    }

    @Test
    void preview_relativeOgImage_isResolvedAgainstFinalUrl() {
        // og:image="/static/cover.jpg" must come back as the absolute
        // URL — the UI needs https://… to put it in an <img> src.
        http.response = PreviewHttpResponse.builder()
                .status(200)
                .finalUri(URI.create("https://blog.example.com/posts/2026/foo"))
                .contentType("text/html")
                .body("""
                        <html><head>
                        <meta property="og:title" content="Foo"/>
                        <meta property="og:image" content="/static/cover.jpg"/>
                        </head></html>
                        """)
                .build();
        LinkPreviewDto dto = service.preview(
                "https://blog.example.com/posts/2026/foo", TENANT, null, null);
        assertThat(dto.getImage()).isEqualTo("https://blog.example.com/static/cover.jpg");
    }

    @Test
    void preview_status404_returnsFailed() {
        http.response = PreviewHttpResponse.builder()
                .status(404)
                .finalUri(URI.create("https://example.com/missing"))
                .contentType("text/html")
                .body("<html>not found</html>")
                .build();
        LinkPreviewDto dto = service.preview(
                "https://example.com/missing", TENANT, null, null);
        assertThat(dto.isOk()).isFalse();
        assertThat(dto.getFailureReason()).isEqualTo("status_404");
        assertThat(dto.getStatus()).isEqualTo(404);
    }

    @Test
    void preview_htmlWithoutMetadata_returnsFailed() {
        // 200 OK but the page has no title, no description, no OG tags
        // — happens on bare API responses, error pages, or stripped-
        // down CDNs. Not useful for a card, so we surface "no_metadata".
        http.response = PreviewHttpResponse.builder()
                .status(200)
                .finalUri(URI.create("https://example.com/empty"))
                .contentType("text/html")
                .body("<html><head></head><body>just text</body></html>")
                .build();
        LinkPreviewDto dto = service.preview(
                "https://example.com/empty", TENANT, null, null);
        assertThat(dto.isOk()).isFalse();
        assertThat(dto.getFailureReason()).isEqualTo("no_metadata");
    }

    @Test
    void preview_pdfContentType_returnsMinimalCard() {
        // PDFs don't have OG-tags, but the link is still valid and
        // worth previewing — render a card with file-name + hostname
        // so the user sees "Q1-Report.pdf • example.com" instead of
        // a naked URL.
        http.response = PreviewHttpResponse.builder()
                .status(200)
                .finalUri(URI.create("https://example.com/docs/Q1-Report.pdf"))
                .contentType("application/pdf")
                .body("%PDF-1.4\n...")
                .build();
        LinkPreviewDto dto = service.preview(
                "https://example.com/docs/Q1-Report.pdf", TENANT, null, null);
        assertThat(dto.isOk()).isTrue();
        assertThat(dto.getTitle()).isEqualTo("Q1-Report.pdf");
        assertThat(dto.getSiteName()).isEqualTo("example.com");
        assertThat(dto.getType()).isEqualTo("pdf");
    }

    @Test
    void preview_invalidScheme_returnsFailedWithoutHttp() {
        LinkPreviewDto dto = service.preview(
                "file:///etc/passwd", TENANT, null, null);
        assertThat(dto.isOk()).isFalse();
        assertThat(dto.getFailureReason()).isEqualTo("scheme_not_http");
        assertThat(http.calls).isZero();
    }

    @Test
    void preview_cacheHit_skipsHttp() {
        LinkPreviewCacheDocument cached = LinkPreviewCacheDocument.builder()
                .url("https://example.com/cached")
                .ok(true)
                .title("Cached Title")
                .siteName("example.com")
                .finalUrl("https://example.com/cached")
                .status(200)
                .fetchedAt(Instant.now())
                .expireAt(Instant.now().plus(Duration.ofHours(1)))
                .build();
        cache.save(cached);
        LinkPreviewDto dto = service.preview(
                "https://example.com/cached", TENANT, null, null);
        assertThat(dto.isOk()).isTrue();
        assertThat(dto.getTitle()).isEqualTo("Cached Title");
        assertThat(http.calls).isZero();
    }

    @Test
    void preview_cacheMiss_writesEntry() {
        http.response = PreviewHttpResponse.builder()
                .status(200)
                .finalUri(URI.create("https://example.com/new"))
                .contentType("text/html")
                .body("<html><head><meta property=\"og:title\" content=\"New\"/></head></html>")
                .build();
        service.preview("https://example.com/new", TENANT, null, null);
        Optional<LinkPreviewCacheDocument> stored = cache.findByUrl("https://example.com/new");
        assertThat(stored).isPresent();
        assertThat(stored.get().isOk()).isTrue();
        assertThat(stored.get().getTitle()).isEqualTo("New");
        assertThat(stored.get().getExpireAt()).isAfter(Instant.now());
    }

    @Test
    void preview_failureCachedWithShortTtl() {
        http.response = PreviewHttpResponse.builder()
                .status(500)
                .finalUri(URI.create("https://example.com/down"))
                .contentType("text/html")
                .body("")
                .build();
        Instant before = Instant.now();
        service.preview("https://example.com/down", TENANT, null, null);
        Optional<LinkPreviewCacheDocument> stored = cache.findByUrl("https://example.com/down");
        assertThat(stored).isPresent();
        assertThat(stored.get().isOk()).isFalse();
        // Failures expire within a couple hours (default 60 min), not
        // the week-long success TTL, so a transient 5xx isn't sticky.
        assertThat(stored.get().getExpireAt())
                .isBetween(before.plus(Duration.ofMinutes(1)),
                        before.plus(Duration.ofHours(6)));
    }

    // ──────────────────────────────────────────────────────────────────
    // Test fakes

    private static class StubHttp implements PreviewHttp {
        PreviewHttpResponse response;
        int calls;

        @Override
        public synchronized PreviewHttpResponse get(
                URI uri, Duration timeout, String userAgent, int maxBodyBytes) {
            calls++;
            return response;
        }
    }

    private static class InMemoryLinkPreviewCache implements LinkPreviewCacheRepository {
        private final Map<String, LinkPreviewCacheDocument> byUrl = new HashMap<>();

        @Override
        public Optional<LinkPreviewCacheDocument> findByUrl(String url) {
            return Optional.ofNullable(byUrl.get(url));
        }

        @Override
        public <S extends LinkPreviewCacheDocument> S save(S entity) {
            byUrl.put(entity.getUrl(), entity);
            return entity;
        }

        @Override public List<LinkPreviewCacheDocument> findAll() { return List.of(); }
        @Override public List<LinkPreviewCacheDocument> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public org.springframework.data.domain.Page<LinkPreviewCacheDocument> findAll(org.springframework.data.domain.Pageable p) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public List<LinkPreviewCacheDocument> findAllById(Iterable<String> ids) { return List.of(); }
        @Override public <S extends LinkPreviewCacheDocument> List<S> saveAll(Iterable<S> entities) {
            List<S> out = new ArrayList<>();
            for (S e : entities) { save(e); out.add(e); }
            return out;
        }
        @Override public <S extends LinkPreviewCacheDocument> List<S> insert(Iterable<S> entities) { return saveAll(entities); }
        @Override public <S extends LinkPreviewCacheDocument> S insert(S entity) { return save(entity); }
        @Override public Optional<LinkPreviewCacheDocument> findById(String id) { return Optional.empty(); }
        @Override public boolean existsById(String id) { return false; }
        @Override public long count() { return byUrl.size(); }
        @Override public void deleteById(String id) { /* no-op */ }
        @Override public void delete(LinkPreviewCacheDocument entity) { byUrl.remove(entity.getUrl()); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { /* no-op */ }
        @Override public void deleteAll(Iterable<? extends LinkPreviewCacheDocument> entities) { /* no-op */ }
        @Override public void deleteAll() { byUrl.clear(); }
        @Override public <S extends LinkPreviewCacheDocument> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends LinkPreviewCacheDocument> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends LinkPreviewCacheDocument> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends LinkPreviewCacheDocument> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends LinkPreviewCacheDocument> long count(org.springframework.data.domain.Example<S> example) { return 0L; }
        @Override public <S extends LinkPreviewCacheDocument> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends LinkPreviewCacheDocument, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> q) {
            throw new UnsupportedOperationException();
        }
    }
}
