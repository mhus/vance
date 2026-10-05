package de.mhus.vance.brain.ai.image.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.image.AiImageConfig;
import de.mhus.vance.brain.ai.image.AiImageException;
import de.mhus.vance.brain.ai.image.AiImageService;
import de.mhus.vance.shared.document.ImageDestinationStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the dedicated OpenRouter image provider: dispatch key,
 * response parsing, {@code usage.cost} pass-through, and error
 * extraction. No HTTP — the request-shaping path is exercised through
 * the dispatch proof and the static parse/write helpers.
 */
class OpenRouterImageProviderTest {

    private static final AiImageConfig CONFIG = new AiImageConfig(
            "openai", "openrouter", "bytedance-seed/seedream-5-0-flash", "sk-or-test", null, "16:9", 60);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void protocol_type_stays_openai_but_dispatch_is_instance_routed() {
        OpenRouterImageProvider provider = new OpenRouterImageProvider("");

        // Protocol stays openai — chat keeps dispatching through the
        // OpenAI wire adapter; only the instance key reroutes images.
        assertThat(provider.getType()).isEqualTo(ProviderType.OPENAI);
        assertThat(provider.getInstanceName()).isEqualTo(Optional.of("openrouter"));
        assertThat(provider.getName()).isEqualTo("openai");
    }

    @Test
    void service_dispatches_openrouter_instance_over_stock_openai_adapter() {
        // The dedicated adapter claims the `openrouter` instance, so
        // AiImageService must pick it over the stock OpenAI adapter
        // even though both speak ProviderType.OPENAI. Proof without
        // network: the dedicated adapter's HTTP call must fail naming
        // OpenRouter (documented /images route), never reach the
        // stock adapter (which would name /images/generations).
        OpenRouterImageProvider dedicated = new OpenRouterImageProvider("");
        de.mhus.vance.brain.ai.image.openai.OpenAiImageProvider stock =
                new de.mhus.vance.brain.ai.image.openai.OpenAiImageProvider("");
        AiImageService service = new AiImageService(List.of(dedicated, stock));
        service.postConstruct();

        assertThatThrownBy(() -> service.generate(CONFIG, "prompt", new RecordingStream()))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("OpenRouter");
    }

    @Test
    void usage_cost_reads_response_number() {
        JsonNode root = MAPPER.readTree("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3600,"
                + "\"total_tokens\":3610,\"cost\":0.018}}");
        assertThat(OpenRouterImageProvider.usageCost(root)).isEqualTo(0.018);
    }

    @Test
    void usage_cost_absent_or_non_numeric_returns_null() {
        assertThat(OpenRouterImageProvider.usageCost(MAPPER.readTree("{\"data\":[]}")))
                .isNull();
        assertThat(OpenRouterImageProvider.usageCost(MAPPER.readTree("{\"usage\":{\"cost\":\"free\"}}")))
                .isNull();
        assertThat(OpenRouterImageProvider.usageCost(MAPPER.readTree("{\"usage\":{}}")))
                .isNull();
    }

    @Test
    void write_to_destination_commits_bytes_media_type_and_cost() {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        String b64 = java.util.Base64.getEncoder().encodeToString(jpeg);
        JsonNode root = MAPPER.readTree("{\"created\":1748372400,"
                + "\"data\":[{\"b64_json\":\"" + b64 + "\","
                + "\"media_type\":\"image/jpeg\"}],"
                + "\"usage\":{\"cost\":0.018}}");
        RecordingStream sink = new RecordingStream();

        OpenRouterImageProvider.writeToDestination(root, CONFIG, 7100L, sink);

        assertThat(sink.mimeType).isEqualTo("image/jpeg");
        assertThat(sink.bytes()).containsExactly(jpeg);
        assertThat(sink.metadata)
                .containsEntry("model", "openrouter:bytedance-seed/seedream-5-0-flash")
                .containsEntry("durationMs", "7100")
                .containsEntry("aspectRatio", "16:9")
                .containsEntry("costUsd", "0.018");
        assertThat(sink.closed).isTrue();
    }

    @Test
    void write_to_destination_sniffs_media_type_when_absent() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        String b64 = java.util.Base64.getEncoder().encodeToString(png);
        JsonNode root = MAPPER.readTree("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}");
        RecordingStream sink = new RecordingStream();

        OpenRouterImageProvider.writeToDestination(root, CONFIG, 100L, sink);

        assertThat(sink.mimeType).isEqualTo("image/png");
        assertThat(sink.metadata).doesNotContainKey("costUsd");
    }

    @Test
    void write_to_destination_throws_on_empty_data() {
        JsonNode root = MAPPER.readTree("{\"data\":[]}");
        assertThatThrownBy(() -> OpenRouterImageProvider.writeToDestination(root, CONFIG, 0L, new RecordingStream()))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("no image data");
    }

    @Test
    void write_to_destination_throws_on_missing_base64() {
        JsonNode root = MAPPER.readTree("{\"data\":[{}]}");
        assertThatThrownBy(() -> OpenRouterImageProvider.writeToDestination(root, CONFIG, 0L, new RecordingStream()))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("no base64 data");
    }

    @Test
    void error_message_extracts_error_object() {
        String body = "{\"error\":{\"code\":404,\"message\":\"Model not found\"}}";
        assertThat(OpenRouterImageProvider.errorMessage(body)).isEqualTo("Model not found");
    }

    @Test
    void error_message_falls_back_to_raw_body() {
        assertThat(OpenRouterImageProvider.errorMessage("plain text oops")).isEqualTo("plain text oops");
        assertThat(OpenRouterImageProvider.errorMessage(null)).isEqualTo("(no response body)");
    }

    /** Capturing stream — records every setter / write call for assertions. */
    static final class RecordingStream extends ImageDestinationStream {
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final Map<String, String> metadata = new LinkedHashMap<>();
        String mimeType;
        boolean closed;

        byte[] bytes() {
            return buffer.toByteArray();
        }

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
        public void setTitle(String t) {
            // not asserted
        }

        @Override
        public void setAltText(String a) {
            // not asserted
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
