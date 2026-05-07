package de.mhus.vance.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link WebOriginOverviewService}. Two facets:
 *
 * <ul>
 *   <li>{@link WebOriginOverviewService#originOf(URI)} as a pure
 *       canonicalisation function — must produce the same key for
 *       URLs that share an origin and elide default ports.</li>
 *   <li>The persistence wrappers ({@code record}, {@code findByOrigin},
 *       {@code delete}) — exercised against a Mockito repository,
 *       focused on the TTL-anchor math and the "ttl<=0 deletes" rule.</li>
 * </ul>
 */
class WebOriginOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-07T20:00:00Z");

    private WebOriginOverviewRepository repository;
    private WebOriginOverviewService service;

    @BeforeEach
    void setUp() {
        repository = mock(WebOriginOverviewRepository.class);
        service = new WebOriginOverviewService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ─── originOf canonicalisation ──────────────────────────────────

    @Test
    void originOf_dropsDefaultHttpPort() {
        assertThat(WebOriginOverviewService.originOf(URI.create("http://example.com:80/foo")))
                .contains("http://example.com");
    }

    @Test
    void originOf_dropsDefaultHttpsPort() {
        assertThat(WebOriginOverviewService.originOf(URI.create("https://example.com:443/foo?x=1")))
                .contains("https://example.com");
    }

    @Test
    void originOf_keepsNonDefaultPort() {
        assertThat(WebOriginOverviewService.originOf(URI.create("https://example.com:8443/foo")))
                .contains("https://example.com:8443");
    }

    @Test
    void originOf_lowercasesSchemeAndHost() {
        assertThat(WebOriginOverviewService.originOf(URI.create("HTTPS://Example.COM/Path")))
                .contains("https://example.com");
    }

    @Test
    void originOf_returnsEmpty_whenSchemeOrHostMissing() {
        assertThat(WebOriginOverviewService.originOf(URI.create("/relative/path"))).isEmpty();
        assertThat(WebOriginOverviewService.originOf(URI.create("mailto:foo@bar"))).isEmpty();
    }

    // ─── record() persistence ───────────────────────────────────────

    @Test
    void record_writesNewRow_withExpireAtNowPlusTtl() {
        when(repository.findByOrigin("https://example.com")).thenReturn(Optional.empty());
        when(repository.save(any(WebOriginOverviewDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebOriginOverviewDocument result = service.record(
                "https://example.com",
                OverviewStatus.OK,
                "# Example\n",
                10,
                Duration.ofHours(2));

        ArgumentCaptor<WebOriginOverviewDocument> captor =
                ArgumentCaptor.forClass(WebOriginOverviewDocument.class);
        verify(repository).save(captor.capture());
        WebOriginOverviewDocument saved = captor.getValue();
        assertThat(saved.getOrigin()).isEqualTo("https://example.com");
        assertThat(saved.getStatus()).isEqualTo(OverviewStatus.OK);
        assertThat(saved.getContent()).isEqualTo("# Example\n");
        assertThat(saved.getContentLength()).isEqualTo(10);
        assertThat(saved.getFetchedAt()).isEqualTo(NOW);
        assertThat(saved.getExpireAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(result).isSameAs(saved);
    }

    @Test
    void record_updatesExistingRow_inPlace() {
        WebOriginOverviewDocument existing = WebOriginOverviewDocument.builder()
                .id("abc")
                .origin("https://example.com")
                .status(OverviewStatus.NOT_FOUND)
                .build();
        when(repository.findByOrigin("https://example.com")).thenReturn(Optional.of(existing));
        when(repository.save(any(WebOriginOverviewDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.record(
                "https://example.com",
                OverviewStatus.OK,
                "body",
                4,
                Duration.ofHours(24));

        ArgumentCaptor<WebOriginOverviewDocument> captor =
                ArgumentCaptor.forClass(WebOriginOverviewDocument.class);
        verify(repository).save(captor.capture());
        WebOriginOverviewDocument saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("abc");
        assertThat(saved.getStatus()).isEqualTo(OverviewStatus.OK);
        assertThat(saved.getContent()).isEqualTo("body");
        assertThat(saved.getExpireAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void record_zeroOrNegativeTtl_deletesInsteadOfWriting() {
        service.record(
                "https://example.com",
                OverviewStatus.OK,
                "body",
                4,
                Duration.ZERO);

        verify(repository).deleteByOrigin("https://example.com");
        verify(repository, never()).save(any());
    }

    @Test
    void record_negativeTtl_deletes() {
        service.record(
                "https://example.com",
                OverviewStatus.OK,
                "body",
                4,
                Duration.ofMinutes(-1));

        verify(repository, times(1)).deleteByOrigin("https://example.com");
        verify(repository, never()).save(any());
    }
}
