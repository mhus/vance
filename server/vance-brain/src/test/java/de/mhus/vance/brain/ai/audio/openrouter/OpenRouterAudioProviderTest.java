package de.mhus.vance.brain.ai.audio.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.audio.AiAudioConfig;
import de.mhus.vance.brain.ai.audio.AiAudioException;
import de.mhus.vance.brain.ai.audio.AnalysisResult;
import de.mhus.vance.brain.ai.audio.AudioSource;
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

    // ──── analysis ──────────────────────────────────────────────────────

    private static final AiAudioConfig AU_CONFIG =
            new AiAudioConfig("openai", "openrouter", "mistralai/voxtral-small-24b-2507", "sk-or-test", null, 120);

    private static final AudioSource AUDIO_SOURCE = new AudioSource(new byte[] {1, 2, 3, 4}, "mp3", "clip.mp3");

    @Test
    void analysis_sends_text_first_audio_last_and_returns_cost() throws Exception {
        HttpClient http = mock(HttpClient.class);
        List<String> bodies = new java.util.ArrayList<>();
        HttpResponse<byte[]> okResponse = jsonResponse(
                200, "{\"choices\":[{\"message\":{\"content\":\"A calm piece.\"}}]," + "\"usage\":{\"cost\":0.00081}}");
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            bodies.add(captureBody(request));
            return okResponse;
        });
        OpenRouterAudioProvider provider = new OpenRouterAudioProvider("", http);

        AnalysisResult result = provider.analyze(AU_CONFIG, AUDIO_SOURCE, "Describe the audio.");

        assertThat(result.text()).isEqualTo("A calm piece.");
        assertThat(result.reportedCostUsd()).isEqualTo(0.00081);
        // Content order is contract, not style: GPT-Audio refuses audio
        // that arrives before the question, so text must be block 0.
        String body = bodies.get(0);
        assertThat(body.indexOf("\"type\":\"text\"")).isLessThan(body.indexOf("\"type\":\"input_audio\""));
        assertThat(body).contains("\"stream\":false");
        assertThat(body).contains("\"format\":\"mp3\"");
        assertThat(body).contains(java.util.Base64.getEncoder().encodeToString(AUDIO_SOURCE.data()));
        assertThat(body).contains("\"model\":\"mistralai/voxtral-small-24b-2507\"");
    }

    @Test
    void analysis_maps_provider_errors_verbosely() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> error = jsonResponse(400, "{\"error\":{\"message\":\"not a valid model ID\"}}");
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenReturn(error);
        OpenRouterAudioProvider provider = new OpenRouterAudioProvider("", http);

        assertThatThrownBy(() -> provider.analyze(AU_CONFIG, AUDIO_SOURCE, "Describe the audio."))
                .isInstanceOf(AiAudioException.class)
                .hasMessageContaining("audio analysis failed (HTTP 400)")
                .hasMessageContaining("not a valid model ID");
    }

    @Test
    void analysis_without_text_fails_closed() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> empty = jsonResponse(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}");
        when(http.<byte[]>send(any(HttpRequest.class), any())).thenReturn(empty);
        OpenRouterAudioProvider provider = new OpenRouterAudioProvider("", http);

        assertThatThrownBy(() -> provider.analyze(AU_CONFIG, AUDIO_SOURCE, "Describe the audio."))
                .isInstanceOf(AiAudioException.class)
                .hasMessageContaining("returned no text");
    }

    /** Drains the request's {@code BodyPublisher} into a String so the
     *  wire body is assertable — the provider builds it via
     *  {@code HttpRequest.BodyPublishers.ofString}. */
    private static String captureBody(HttpRequest request) {
        try {
            java.net.http.HttpRequest.BodyPublisher publisher =
                    request.bodyPublisher().orElseThrow();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            var flow = (java.util.concurrent.Flow.Publisher<java.nio.ByteBuffer>) publisher;
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            flow.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(java.nio.ByteBuffer item) {
                    byte[] b = new byte[item.remaining()];
                    item.get(b);
                    out.write(b, 0, b.length);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onComplete() {
                    done.countDown();
                }
            });
            done.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
