package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.web.OverviewStatus;
import de.mhus.vance.shared.web.WebOriginOverviewDocument;
import de.mhus.vance.shared.web.WebOriginOverviewService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link LlmsTxtProbeService}. The HTTP client and
 * cache are mocked, so each test asserts on the deterministic
 * Setting → Cache → HTTP → Cache-record flow.
 */
class LlmsTxtProbeServiceTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext(
            "tenant-1", "proj-1", "session-1", "process-1", "user-1");
    private static final URI USER_URL = URI.create("https://example.com/some/page");
    private static final String EXPECTED_ORIGIN = "https://example.com";

    private WebOriginOverviewService cache;
    private SettingService settings;
    private HttpClient http;
    private LlmsTxtProbeService probe;

    @BeforeEach
    void setUp() {
        cache = mock(WebOriginOverviewService.class);
        settings = mock(SettingService.class);
        http = mock(HttpClient.class);
        probe = new LlmsTxtProbeService(cache, settings, http);
    }

    // ─── setting toggle ─────────────────────────────────────────────

    @Test
    void probe_returnsEmpty_andSkipsLookup_whenSettingDisabled() throws Exception {
        when(settings.getStringValueCascade(
                "tenant-1", "proj-1", "process-1", LlmsTxtProbeService.SETTING_AUTO_PROBE))
                .thenReturn("false");

        Optional<String> result = probe.probe(USER_URL, CTX);

        assertThat(result).isEmpty();
        verifyNoInteractions(cache);
        verify(http, never()).send(any(), any());
    }

    @Test
    void probe_appliesDefaultTrue_whenSettingMissing() throws Exception {
        when(settings.getStringValueCascade(
                "tenant-1", "proj-1", "process-1", LlmsTxtProbeService.SETTING_AUTO_PROBE))
                .thenReturn(null);
        when(cache.findByOrigin(EXPECTED_ORIGIN)).thenReturn(Optional.empty());
        givenHttpResponse(404, "");

        probe.probe(USER_URL, CTX);

        verify(cache).findByOrigin(EXPECTED_ORIGIN);
    }

    // ─── cache hits ─────────────────────────────────────────────────

    @Test
    void probe_returnsCachedContent_onCacheHitOk() throws Exception {
        givenAutoProbeEnabled();
        WebOriginOverviewDocument hit = WebOriginOverviewDocument.builder()
                .origin(EXPECTED_ORIGIN)
                .status(OverviewStatus.OK)
                .content("# Example\n- [Docs](/docs.md)")
                .contentLength(30)
                .build();
        when(cache.findByOrigin(EXPECTED_ORIGIN)).thenReturn(Optional.of(hit));

        Optional<String> result = probe.probe(USER_URL, CTX);

        assertThat(result).contains("# Example\n- [Docs](/docs.md)");
        verify(http, never()).send(any(), any());
    }

    @Test
    void probe_returnsEmpty_onCacheHitNotFound() throws Exception {
        givenAutoProbeEnabled();
        WebOriginOverviewDocument hit = WebOriginOverviewDocument.builder()
                .origin(EXPECTED_ORIGIN)
                .status(OverviewStatus.NOT_FOUND)
                .build();
        when(cache.findByOrigin(EXPECTED_ORIGIN)).thenReturn(Optional.of(hit));

        Optional<String> result = probe.probe(USER_URL, CTX);

        assertThat(result).isEmpty();
        verify(http, never()).send(any(), any());
    }

    // ─── cache miss → HTTP ──────────────────────────────────────────

    @Test
    void probe_fetchesAndCaches_on200() throws Exception {
        givenAutoProbeEnabled();
        when(cache.findByOrigin(EXPECTED_ORIGIN)).thenReturn(Optional.empty());
        givenHttpResponse(200, "# Example Site\n");

        Optional<String> result = probe.probe(USER_URL, CTX);

        assertThat(result).contains("# Example Site\n");
        verify(cache).record(
                eq(EXPECTED_ORIGIN),
                eq(OverviewStatus.OK),
                eq("# Example Site\n"),
                eq(15),
                any(Duration.class));
    }

    @Test
    void probe_truncatesOversizeContent_andRecordsOriginalLength() throws Exception {
        givenAutoProbeEnabled();
        when(cache.findByOrigin(EXPECTED_ORIGIN)).thenReturn(Optional.empty());
        when(settings.getStringValueCascade(
                "tenant-1", "proj-1", "process-1",
                LlmsTxtProbeService.SETTING_MAX_CONTENT_CHARS))
                .thenReturn("100");
        String body = "x".repeat(250);
        givenHttpResponse(200, body);

        probe.probe(USER_URL, CTX);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> lengthCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(cache).record(
                eq(EXPECTED_ORIGIN),
                eq(OverviewStatus.OK),
                contentCaptor.capture(),
                lengthCaptor.capture(),
                any(Duration.class));
        assertThat(contentCaptor.getValue()).hasSize(100);
        assertThat(lengthCaptor.getValue()).isEqualTo(250);
    }

    @Test
    void probe_caches404AsNotFound_andUsesNegativeTtl() throws Exception {
        givenAutoProbeEnabled();
        when(cache.findByOrigin(EXPECTED_ORIGIN)).thenReturn(Optional.empty());
        givenHttpResponse(404, "");

        Optional<String> result = probe.probe(USER_URL, CTX);

        assertThat(result).isEmpty();
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(cache).record(
                eq(EXPECTED_ORIGIN),
                eq(OverviewStatus.NOT_FOUND),
                eq(null),
                eq(0),
                ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(
                Duration.ofHours(LlmsTxtProbeService.DEFAULT_NEGATIVE_TTL_HOURS));
    }

    @Test
    void probe_caches5xxAsError_withShortTtl() throws Exception {
        givenAutoProbeEnabled();
        when(cache.findByOrigin(EXPECTED_ORIGIN)).thenReturn(Optional.empty());
        givenHttpResponse(503, "");

        probe.probe(USER_URL, CTX);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(cache).record(
                eq(EXPECTED_ORIGIN),
                eq(OverviewStatus.ERROR),
                eq(null),
                eq(0),
                ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(
                Duration.ofMinutes(LlmsTxtProbeService.DEFAULT_ERROR_TTL_MINUTES));
    }

    @Test
    void probe_handlesTransportException_asError() throws Exception {
        givenAutoProbeEnabled();
        when(cache.findByOrigin(EXPECTED_ORIGIN)).thenReturn(Optional.empty());
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"));

        Optional<String> result = probe.probe(USER_URL, CTX);

        assertThat(result).isEmpty();
        verify(cache).record(
                eq(EXPECTED_ORIGIN),
                eq(OverviewStatus.ERROR),
                eq(null),
                eq(0),
                any(Duration.class));
    }

    // ─── unparseable origin ─────────────────────────────────────────

    @Test
    void probe_returnsEmpty_whenUrlHasNoHost() throws Exception {
        givenAutoProbeEnabled();

        Optional<String> result = probe.probe(URI.create("file:///tmp/local"), CTX);

        assertThat(result).isEmpty();
        verifyNoInteractions(cache);
        verify(http, never()).send(any(), any());
    }

    // ─── helpers ────────────────────────────────────────────────────

    private void givenAutoProbeEnabled() {
        when(settings.getStringValueCascade(
                "tenant-1", "proj-1", "process-1", LlmsTxtProbeService.SETTING_AUTO_PROBE))
                .thenReturn("true");
    }

    @SuppressWarnings("unchecked")
    private void givenHttpResponse(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }
}
