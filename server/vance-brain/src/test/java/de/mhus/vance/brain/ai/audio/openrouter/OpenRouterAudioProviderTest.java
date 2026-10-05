package de.mhus.vance.brain.ai.audio.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.audio.AiAudioConfig;
import de.mhus.vance.brain.ai.audio.AiAudioException;
import de.mhus.vance.brain.ai.audio.TtsRequest;
import de.mhus.vance.shared.document.AudioDestinationStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the TTS wire path of the OpenRouter audio
 * provider — in particular the pcm-retry: the model rejects the format
 * once (400 naming {@code response_format}), the provider retries as
 * {@code pcm} and wraps the bytes into a WAV container. The retry's own
 * response must be status-checked: a failed retry is an error body, and
 * wrapping that as audio would commit a corrupt document and book the
 * call as a success.
 */
class OpenRouterAudioProviderTest {

    private static final AiAudioConfig CONFIG =
            new AiAudioConfig("openai", "openrouter", "google/gemini-3.8-flash-lite-tts", "sk-or-test", null, 60);

    private static final TtsRequest WAV_REQUEST = new TtsRequest("hello", "en", null, "wav", null);

    @Test
    void failed_pcm_retry_throws_instead_of_committing_the_error_body() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> formatRejection =
                jsonResponse(400, "{\"error\":{\"message\":\"response_format must be pcm\"}}");
        HttpResponse<byte[]> serverError = jsonResponse(500, "{\"error\":{\"message\":\"rate limited\"}}");
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenReturn(formatRejection, serverError);
        OpenRouterAudioProvider provider = new OpenRouterAudioProvider("", http);
        RecordingStream stream = new RecordingStream();

        assertThatThrownBy(() -> provider.synthesize(CONFIG, WAV_REQUEST, stream))
                .isInstanceOf(AiAudioException.class)
                .hasMessageContaining("pcm retry failed (HTTP 500)")
                .hasMessageContaining("rate limited");
        assertThat(stream.closed).isFalse();
        assertThat(stream.bytes.size()).isZero();
    }

    @Test
    void pcm_retry_wraps_raw_pcm_into_a_wav_document() throws Exception {
        byte[] pcm = new byte[] {0, 1, 2, 3};
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> formatRejection =
                jsonResponse(400, "{\"error\":{\"message\":\"response_format must be pcm\"}}");
        HttpResponse<byte[]> pcmAudio = rawResponse(200, pcm, "audio/pcm;rate=24000;channels=1");
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenReturn(formatRejection, pcmAudio);
        OpenRouterAudioProvider provider = new OpenRouterAudioProvider("", http);
        RecordingStream stream = new RecordingStream();

        provider.synthesize(CONFIG, WAV_REQUEST, stream);

        assertThat(stream.closed).isTrue();
        assertThat(stream.mimeType).isEqualTo("audio/wav");
        byte[] committed = stream.bytes.toByteArray();
        assertThat(committed).hasSizeGreaterThan(pcm.length);
        assertThat(new String(committed, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
    }

    // ──── helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> jsonResponse(int status, String body) {
        return rawResponse(status, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> rawResponse(int status, byte[] body, String contentType) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers())
                .thenReturn(HttpHeaders.of(Map.of("content-type", List.of(contentType)), (a, b) -> true));
        return response;
    }

    private static final class RecordingStream extends AudioDestinationStream {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private String mimeType;
        private boolean closed;

        @Override
        public void write(int b) {
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes.write(b, off, len);
        }

        @Override
        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        @Override
        public void setTitle(@Nullable String title) {
            // not under test
        }

        @Override
        public void setMetadata(String key, String value) {
            // not under test
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
