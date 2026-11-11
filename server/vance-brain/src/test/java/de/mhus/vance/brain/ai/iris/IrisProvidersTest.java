package de.mhus.vance.brain.ai.iris;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.audio.AiAudioConfig;
import de.mhus.vance.brain.ai.audio.AiAudioException;
import de.mhus.vance.brain.ai.audio.AudioSource;
import de.mhus.vance.brain.ai.audio.SttResult;
import de.mhus.vance.brain.ai.image.AiImageConfig;
import de.mhus.vance.brain.ai.image.AiImageException;
import de.mhus.vance.brain.ai.image.AiImageService;
import de.mhus.vance.brain.ai.image.openrouter.OpenRouterImageProvider;
import de.mhus.vance.shared.document.ImageDestinationStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Iris image and audio adapters against a real local HTTP server that
 * answers the way Iris does: the OpenRouter wire, with the vendor's USD cost
 * passed through in the body — which these adapters must not report.
 */
class IrisProvidersTest {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(String route, String json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(route, exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void theAdaptersAreSelectedByTheIrisProtocolAndClaimNoInstanceName() {
        assertThat(new IrisImageProvider().getType()).isEqualTo(ProviderType.IRIS);
        assertThat(new IrisImageProvider().getInstanceName()).isEqualTo(Optional.empty());
        assertThat(new IrisAudioProvider().getType()).isEqualTo(ProviderType.IRIS);
        assertThat(new IrisAudioProvider().getInstanceName()).isEqualTo(Optional.empty());
    }

    @Test
    void imageDispatchReachesTheIrisAdapterAndItRefusesToRunWithoutABaseUrl() {
        AiImageService service = new AiImageService(List.of(new OpenRouterImageProvider(""), new IrisImageProvider()));
        service.postConstruct();
        AiImageConfig config = new AiImageConfig("iris", "iris", "eu-image", "vk", null, "1:1", 30);

        assertThatThrownBy(() -> service.generate(config, "a circle", new Sink()))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("ai.provider.<instance>.baseUrl");
    }

    @Test
    void anImageGoesToSlashImagesAndTheVendorCostIsNotReported() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        String base = serve(
                "/v1/images",
                "{\"created\":0,\"data\":[{\"b64_json\":\""
                        + Base64.getEncoder().encodeToString(jpeg) + "\",\"media_type\":\"image/jpeg\"}],"
                        + "\"usage\":{\"cost\":0.009}}");
        AiImageConfig config = new AiImageConfig("iris", "iris", "eu-image", "vk", base, "1:1", 30);
        Sink sink = new Sink();

        new IrisImageProvider().generate(config, "a circle", sink);

        assertThat(paths).containsExactly("/v1/images");
        assertThat(sink.mimeType).isEqualTo("image/jpeg");
        assertThat(sink.metadata).doesNotContainKey("costUsd");
        assertThat(sink.closed).isTrue();
    }

    @Test
    void theOpenRouterAdapterStillReportsItsVendorCost() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        String base = serve(
                "/v1/images",
                "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(jpeg)
                        + "\"}],\"usage\":{\"cost\":0.009}}");
        AiImageConfig config = new AiImageConfig("openai", "openrouter", "m", "k", base, "1:1", 30);
        Sink sink = new Sink();

        new OpenRouterImageProvider("").generate(config, "x", sink);

        assertThat(sink.metadata).containsEntry("costUsd", "0.009");
    }

    @Test
    void aTranscriptionKeepsTheSecondsButNotTheVendorCost() throws Exception {
        String base =
                serve("/v1/audio/transcriptions", "{\"text\":\"hello\",\"usage\":{\"seconds\":2.5,\"cost\":0.00005}}");
        AiAudioConfig config = new AiAudioConfig("iris", "iris", "eu-stt", "vk", base, 30);

        SttResult result =
                new IrisAudioProvider().transcribe(config, new AudioSource(new byte[] {1, 2, 3}, "wav", "a.wav"), null);

        assertThat(paths).containsExactly("/v1/audio/transcriptions");
        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.durationSeconds()).isEqualTo(2.5);
        assertThat(result.reportedCostUsd()).isNull();
    }

    @Test
    void audioRefusesToRunWithoutABaseUrl() {
        AiAudioConfig config = new AiAudioConfig("iris", "iris", "eu-stt", "vk", null, 30);

        assertThatThrownBy(() ->
                        new IrisAudioProvider().transcribe(config, new AudioSource(new byte[] {1}, "wav", null), null))
                .isInstanceOf(AiAudioException.class)
                .hasMessageContaining("baseUrl");
    }

    private static final class Sink extends ImageDestinationStream {
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final Map<String, String> metadata = new LinkedHashMap<>();
        String mimeType;
        boolean closed;

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public void setMimeType(String mime) {
            this.mimeType = mime;
        }

        @Override
        public void setMetadata(String k, String v) {
            metadata.put(k, v);
        }

        @Override
        public void setTitle(String t) {}

        @Override
        public void setAltText(String a) {}

        @Override
        public void close() {
            closed = true;
        }
    }
}
