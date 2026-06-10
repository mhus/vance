package de.mhus.vance.brain.ai.image.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.image.AiImageConfig;
import de.mhus.vance.brain.ai.image.AiImageException;
import de.mhus.vance.shared.document.ImageDestinationStream;
import dev.langchain4j.data.image.Image;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiImageProviderTest {

    private static final AiImageConfig CONFIG = new AiImageConfig(
            "gemini", "gemini", "gemini-2.5-flash-image",
            "AIza-test", null, "16:9", 90);

    @Test
    void provider_type_is_gemini() {
        GeminiImageProvider p = new GeminiImageProvider("");

        assertThat(p.getType()).isEqualTo(ProviderType.GEMINI);
        assertThat(p.getName()).isEqualTo("gemini");
    }

    @Test
    void resolve_mime_type_falls_back_to_png_for_blank() {
        assertThat(GeminiImageProvider.resolveMimeType(null))
                .isEqualTo("image/png");
        assertThat(GeminiImageProvider.resolveMimeType("  "))
                .isEqualTo("image/png");
    }

    @Test
    void resolve_mime_type_preserves_reported_value() {
        assertThat(GeminiImageProvider.resolveMimeType("image/webp"))
                .isEqualTo("image/webp");
    }

    @Test
    void decode_bytes_returns_decoded_payload() {
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[] {10, 20, 30});
        Image image = Image.builder().base64Data(b64).build();

        byte[] decoded = GeminiImageProvider.decodeBytes(image, CONFIG);

        assertThat(decoded).containsExactly(10, 20, 30);
    }

    @Test
    void decode_bytes_throws_on_missing_base64() {
        Image image = Image.builder().build();

        assertThatThrownBy(() -> GeminiImageProvider.decodeBytes(image, CONFIG))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("no base64");
    }

    @Test
    void decode_bytes_throws_on_invalid_base64() {
        Image image = Image.builder().base64Data("###").build();

        assertThatThrownBy(() -> GeminiImageProvider.decodeBytes(image, CONFIG))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("decode");
    }

    @Test
    void write_to_destination_commits_bytes_and_metadata() {
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[] {2, 4, 6, 8});
        Image image = Image.builder()
                .base64Data(b64)
                .mimeType("image/png")
                .build();
        RecordingStream sink = new RecordingStream();

        GeminiImageProvider.writeToDestination(image, CONFIG, 555L, sink);

        assertThat(sink.mimeType).isEqualTo("image/png");
        assertThat(sink.bytes()).containsExactly(2, 4, 6, 8);
        assertThat(sink.metadata)
                .containsEntry("model", "gemini:gemini-2.5-flash-image")
                .containsEntry("durationMs", "555")
                .containsEntry("aspectRatio", "16:9");
        assertThat(sink.metadata).doesNotContainKey("revisedPrompt");
        assertThat(sink.closed).isTrue();
    }

    @Test
    void write_to_destination_throws_on_null_image() {
        assertThatThrownBy(() -> GeminiImageProvider.writeToDestination(
                null, CONFIG, 0L, new RecordingStream()))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("no image");
    }

    /** Capturing stream — records every setter / write call for assertions. */
    static final class RecordingStream extends ImageDestinationStream {
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final Map<String, String> metadata = new LinkedHashMap<>();
        String mimeType;
        String title;
        String altText;
        boolean closed;

        byte[] bytes() { return buffer.toByteArray(); }

        @Override public void write(int b) { buffer.write(b); }
        @Override public void write(byte[] b, int off, int len) { buffer.write(b, off, len); }
        @Override public void setMimeType(String mime) { this.mimeType = mime; }
        @Override public void setTitle(String t) { this.title = t; }
        @Override public void setMetadata(String k, String v) { metadata.put(k, v); }
        @Override public void setAltText(String a) { this.altText = a; }
        @Override public void close() { this.closed = true; }
    }
}
