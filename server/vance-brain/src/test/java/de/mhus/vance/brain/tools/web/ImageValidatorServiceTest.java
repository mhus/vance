package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.web.ImageValidatorService.ImageValidatorHttp;
import de.mhus.vance.brain.tools.web.ImageValidatorService.ProbeResponse;
import de.mhus.vance.brain.tools.web.ImageValidatorService.ValidationResult;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.web.ImageUrlCacheDocument;
import de.mhus.vance.shared.web.ImageUrlCacheRepository;
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
 * Unit tests for {@link ImageValidatorService}. Cover the rules the
 * service is supposed to enforce — image vs. html, redirect-to-root
 * fallback, HEAD→GET-Range fallback, magic-number sniff, cache reuse.
 * Uses an in-memory cache stub and a hand-rolled HTTP stub so no
 * real network is involved.
 */
class ImageValidatorServiceTest {

    private static final String TENANT = "acme";
    private static final URI IMAGE_URI = URI.create("https://example.com/pic.jpg");

    private InMemoryImageUrlCache cache;
    private StubHttp http;
    private SettingService settings;
    private ImageValidatorService service;

    @BeforeEach
    void setUp() {
        cache = new InMemoryImageUrlCache();
        http = new StubHttp();
        settings = mock(SettingService.class);
        // All integer settings unset → defaults apply.
        when(settings.getStringValueCascade(any(), any(), any(), any())).thenReturn(null);
        service = new ImageValidatorService(cache, settings, http);
    }

    @Test
    void validateOne_imageJpeg_returnsOk() {
        http.headResponse = ProbeResponse.builder()
                .status(200).finalUri(IMAGE_URI)
                .contentType("image/jpeg")
                .sample(null).build();
        ValidationResult result = service.validateOne(IMAGE_URI.toString(), TENANT, null, null);
        assertThat(result.isOk()).isTrue();
        assertThat(result.getContentType()).isEqualTo("image/jpeg");
        assertThat(result.getStatus()).isEqualTo(200);
    }

    @Test
    void validateOne_textHtml_returnsFailed() {
        URI uri = URI.create("https://thehappyjetlagger.com/wp-content/uploads/foo.jpg");
        http.headResponse = ProbeResponse.builder()
                // The Lisbon reproducer: server returns 200 but the body is
                // HTML, not an image.
                .status(200).finalUri(uri)
                .contentType("text/html; charset=UTF-8")
                .sample(null).build();
        ValidationResult result = service.validateOne(uri.toString(), TENANT, null, null);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getReason()).startsWith("content_type_text/html");
    }

    @Test
    void validateOne_redirectToHomepage_returnsFailed() {
        URI homepage = URI.create("https://thehappyjetlagger.com/");
        http.headResponse = ProbeResponse.builder()
                // Server says 200 image/* but the final URI's path is just
                // "/" — classic WordPress missing-image-redirects-to-home.
                .status(200).finalUri(homepage)
                .contentType("image/jpeg")
                .sample(null).build();
        ValidationResult result = service.validateOne(IMAGE_URI.toString(), TENANT, null, null);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getReason()).isEqualTo("homepage_fallback");
        assertThat(result.getFinalUrl()).isEqualTo("https://thehappyjetlagger.com/");
    }

    @Test
    void validateOne_status404_returnsFailed() {
        http.headResponse = ProbeResponse.builder()
                .status(404).finalUri(IMAGE_URI).contentType(null).sample(null).build();
        ValidationResult result = service.validateOne(IMAGE_URI.toString(), TENANT, null, null);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getReason()).isEqualTo("status_404");
    }

    @Test
    void validateOne_head405_fallsBackToGetRange() {
        // HEAD blocked → service must retry with a small Range GET.
        // Body comes back with a JPEG magic number; the validator must
        // pick that up via content-type, not magic-number sniff (the
        // GET-Range response carries Content-Type already).
        http.headResponse = ProbeResponse.builder()
                .status(405).finalUri(IMAGE_URI).contentType(null).sample(null).build();
        http.rangeResponse = ProbeResponse.builder()
                .status(200).finalUri(IMAGE_URI)
                .contentType("image/jpeg")
                .sample(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})
                .build();
        ValidationResult result = service.validateOne(IMAGE_URI.toString(), TENANT, null, null);
        assertThat(result.isOk()).isTrue();
        assertThat(http.headCalls).isEqualTo(1);
        assertThat(http.rangeCalls).isEqualTo(1);
    }

    @Test
    void validateOne_noContentTypeButMagicNumberMatches_returnsOk() {
        // Some CDNs ship image bytes with Content-Type missing or
        // application/octet-stream. The Range-GET fallback sniffs
        // the magic number and rescues the verdict.
        http.headResponse = ProbeResponse.builder()
                .status(200).finalUri(IMAGE_URI)
                .contentType("application/octet-stream")
                .sample(null).build();
        http.rangeResponse = ProbeResponse.builder()
                .status(200).finalUri(IMAGE_URI)
                .contentType("application/octet-stream")
                .sample(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})
                .build();
        ValidationResult result = service.validateOne(IMAGE_URI.toString(), TENANT, null, null);
        assertThat(result.isOk()).isTrue();
        assertThat(result.getContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void looksLikeImageBytes_recognisesCommonFormats() {
        // Sanity-check the sniffer table inline — keeps the public
        // contract documented even if probe() refactors.
        assertThat(ImageValidatorService.looksLikeImageBytes(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})).isTrue(); // JFIF
        assertThat(ImageValidatorService.looksLikeImageBytes(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})).isTrue(); // PNG
        assertThat(ImageValidatorService.looksLikeImageBytes(
                "GIF89a".getBytes())).isTrue(); // GIF
        assertThat(ImageValidatorService.looksLikeImageBytes(
                "RIFF\0\0\0\0WEBP".getBytes())).isTrue();
        assertThat(ImageValidatorService.looksLikeImageBytes(
                "<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes())).isTrue();
        assertThat(ImageValidatorService.looksLikeImageBytes(
                "<html>".getBytes())).isFalse();
        assertThat(ImageValidatorService.looksLikeImageBytes(null)).isFalse();
        assertThat(ImageValidatorService.looksLikeImageBytes(new byte[] {1, 2})).isFalse();
    }

    @Test
    void validateOne_cachedOk_skipsHttp() {
        ImageUrlCacheDocument doc = ImageUrlCacheDocument.builder()
                .url(IMAGE_URI.toString())
                .ok(true)
                .contentType("image/jpeg")
                .finalUrl(IMAGE_URI.toString())
                .status(200)
                .validatedAt(Instant.now())
                .expireAt(Instant.now().plus(Duration.ofHours(1)))
                .build();
        cache.save(doc);
        ValidationResult result = service.validateOne(IMAGE_URI.toString(), TENANT, null, null);
        assertThat(result.isOk()).isTrue();
        assertThat(result.getContentType()).isEqualTo("image/jpeg");
        assertThat(http.headCalls).isZero();
    }

    @Test
    void validateOne_cacheMiss_writesEntry() {
        http.headResponse = ProbeResponse.builder()
                .status(200).finalUri(IMAGE_URI).contentType("image/png").sample(null).build();
        service.validateOne(IMAGE_URI.toString(), TENANT, null, null);
        Optional<ImageUrlCacheDocument> stored = cache.findByUrl(IMAGE_URI.toString());
        assertThat(stored).isPresent();
        assertThat(stored.get().isOk()).isTrue();
        assertThat(stored.get().getContentType()).isEqualTo("image/png");
        assertThat(stored.get().getExpireAt()).isNotNull();
    }

    @Test
    void validateOne_cacheMiss_failure_writesShortTtlEntry() {
        http.headResponse = ProbeResponse.builder()
                .status(404).finalUri(IMAGE_URI).contentType(null).sample(null).build();
        Instant before = Instant.now();
        service.validateOne(IMAGE_URI.toString(), TENANT, null, null);
        Optional<ImageUrlCacheDocument> stored = cache.findByUrl(IMAGE_URI.toString());
        assertThat(stored).isPresent();
        assertThat(stored.get().isOk()).isFalse();
        // Failure TTL is short (~30 min default), so expire-at is well
        // under 24h. Compare against a half-day midpoint to keep the
        // assertion robust against clock drift inside the test.
        assertThat(stored.get().getExpireAt())
                .isBetween(before.plus(Duration.ofMinutes(1)),
                        before.plus(Duration.ofHours(12)));
    }

    @Test
    void validate_batch_returnsResultsInInputOrder() {
        http.responsesByHost = new HashMap<>();
        http.responsesByHost.put("a.example.com", ProbeResponse.builder()
                .status(200).finalUri(URI.create("https://a.example.com/x.jpg"))
                .contentType("image/jpeg").sample(null).build());
        http.responsesByHost.put("b.example.com", ProbeResponse.builder()
                .status(404).finalUri(URI.create("https://b.example.com/x.jpg"))
                .contentType(null).sample(null).build());
        http.responsesByHost.put("c.example.com", ProbeResponse.builder()
                .status(200).finalUri(URI.create("https://c.example.com/x.jpg"))
                .contentType("image/png").sample(null).build());
        List<String> urls = List.of(
                "https://a.example.com/x.jpg",
                "https://b.example.com/x.jpg",
                "https://c.example.com/x.jpg");
        List<ValidationResult> results = service.validate(urls, TENANT, null, null);
        assertThat(results).hasSize(3);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(results.get(1).isOk()).isFalse();
        assertThat(results.get(2).isOk()).isTrue();
    }

    @Test
    void validate_batch_dedupsRepeatedUrls() {
        http.headResponse = ProbeResponse.builder()
                .status(200).finalUri(IMAGE_URI).contentType("image/jpeg").sample(null).build();
        List<String> urls = List.of(
                IMAGE_URI.toString(), IMAGE_URI.toString(), IMAGE_URI.toString());
        List<ValidationResult> results = service.validate(urls, TENANT, null, null);
        assertThat(results).hasSize(3);
        // Only one network call though the input had three entries.
        assertThat(http.headCalls).isEqualTo(1);
    }

    @Test
    void validateOne_invalidScheme_returnsFailedWithoutHttp() {
        ValidationResult result = service.validateOne(
                "file:///etc/passwd", TENANT, null, null);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getReason()).isEqualTo("scheme_not_http");
        assertThat(http.headCalls).isZero();
    }

    // ──────────────────────────────────────────────────────────────────
    // Test fakes

    /** Minimal HTTP stub — single response or per-host map for batches. */
    private static class StubHttp implements ImageValidatorHttp {
        ProbeResponse headResponse;
        ProbeResponse rangeResponse;
        Map<String, ProbeResponse> responsesByHost;
        int headCalls;
        int rangeCalls;

        @Override
        public synchronized ProbeResponse head(URI uri, Duration timeout, String userAgent) {
            headCalls++;
            if (responsesByHost != null) {
                ProbeResponse byHost = responsesByHost.get(uri.getHost());
                if (byHost != null) return byHost;
            }
            return headResponse;
        }

        @Override
        public synchronized ProbeResponse getRange(URI uri, Duration timeout, String userAgent) {
            rangeCalls++;
            return rangeResponse == null ? headResponse : rangeResponse;
        }
    }

    /** In-memory cache stub. Implements only the two methods we need. */
    private static class InMemoryImageUrlCache implements ImageUrlCacheRepository {
        private final Map<String, ImageUrlCacheDocument> byUrl = new HashMap<>();

        @Override
        public Optional<ImageUrlCacheDocument> findByUrl(String url) {
            return Optional.ofNullable(byUrl.get(url));
        }

        @Override
        public <S extends ImageUrlCacheDocument> S save(S entity) {
            byUrl.put(entity.getUrl(), entity);
            return entity;
        }

        // -------- Unused MongoRepository methods — empty stubs --------
        @Override public List<ImageUrlCacheDocument> findAll() { return List.of(); }
        @Override public List<ImageUrlCacheDocument> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public org.springframework.data.domain.Page<ImageUrlCacheDocument> findAll(org.springframework.data.domain.Pageable p) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public List<ImageUrlCacheDocument> findAllById(Iterable<String> ids) { return List.of(); }
        @Override public <S extends ImageUrlCacheDocument> List<S> saveAll(Iterable<S> entities) {
            List<S> out = new ArrayList<>();
            for (S e : entities) { save(e); out.add(e); }
            return out;
        }
        @Override public <S extends ImageUrlCacheDocument> List<S> insert(Iterable<S> entities) { return saveAll(entities); }
        @Override public <S extends ImageUrlCacheDocument> S insert(S entity) { return save(entity); }
        @Override public Optional<ImageUrlCacheDocument> findById(String id) { return Optional.empty(); }
        @Override public boolean existsById(String id) { return false; }
        @Override public long count() { return byUrl.size(); }
        @Override public void deleteById(String id) { /* no-op */ }
        @Override public void delete(ImageUrlCacheDocument entity) { byUrl.remove(entity.getUrl()); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { /* no-op */ }
        @Override public void deleteAll(Iterable<? extends ImageUrlCacheDocument> entities) { /* no-op */ }
        @Override public void deleteAll() { byUrl.clear(); }
        @Override public <S extends ImageUrlCacheDocument> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends ImageUrlCacheDocument> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends ImageUrlCacheDocument> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends ImageUrlCacheDocument> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends ImageUrlCacheDocument> long count(org.springframework.data.domain.Example<S> example) { return 0L; }
        @Override public <S extends ImageUrlCacheDocument> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends ImageUrlCacheDocument, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> q) {
            throw new UnsupportedOperationException();
        }
    }

}
