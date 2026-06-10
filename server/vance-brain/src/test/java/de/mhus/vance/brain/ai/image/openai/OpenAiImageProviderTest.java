package de.mhus.vance.brain.ai.image.openai;

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

class OpenAiImageProviderTest {

    private static final AiImageConfig CONFIG = new AiImageConfig(
            "openai", "openai", "gpt-image-1", "sk-test",
            null, "1:1", 360);

    @Test
    void provider_type_is_openai() {
        OpenAiImageProvider p = new OpenAiImageProvider("https://api.openai.com/v1");

        assertThat(p.getType()).isEqualTo(ProviderType.OPENAI);
        assertThat(p.getName()).isEqualTo("openai");
    }

    @Test
    void map_aspect_ratio_square_uses_square_pixel_size() {
        assertThat(OpenAiImageProvider.mapAspectRatioToSize("1:1"))
                .isEqualTo("1024x1024");
    }

    @Test
    void map_aspect_ratio_landscape_uses_landscape_pixel_size() {
        assertThat(OpenAiImageProvider.mapAspectRatioToSize("16:9"))
                .isEqualTo("1536x1024");
        assertThat(OpenAiImageProvider.mapAspectRatioToSize("4:3"))
                .isEqualTo("1536x1024");
    }

    @Test
    void map_aspect_ratio_portrait_uses_portrait_pixel_size() {
        assertThat(OpenAiImageProvider.mapAspectRatioToSize("9:16"))
                .isEqualTo("1024x1536");
        assertThat(OpenAiImageProvider.mapAspectRatioToSize("3:4"))
                .isEqualTo("1024x1536");
    }

    @Test
    void map_aspect_ratio_unknown_falls_back_to_auto() {
        assertThat(OpenAiImageProvider.mapAspectRatioToSize("21:9"))
                .isEqualTo("auto");
        assertThat(OpenAiImageProvider.mapAspectRatioToSize(""))
                .isEqualTo("auto");
    }

    @Test
    void resolve_mime_type_falls_back_to_png_for_blank() {
        assertThat(OpenAiImageProvider.resolveMimeType(null))
                .isEqualTo("image/png");
        assertThat(OpenAiImageProvider.resolveMimeType("  "))
                .isEqualTo("image/png");
    }

    @Test
    void resolve_mime_type_preserves_reported_value() {
        assertThat(OpenAiImageProvider.resolveMimeType("image/jpeg"))
                .isEqualTo("image/jpeg");
    }

    @Test
    void decode_bytes_returns_decoded_payload() {
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5});
        Image image = Image.builder().base64Data(b64).build();

        byte[] decoded = OpenAiImageProvider.decodeBytes(image, CONFIG);

        assertThat(decoded).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void decode_bytes_throws_on_missing_base64() {
        Image image = Image.builder().build();

        assertThatThrownBy(() -> OpenAiImageProvider.decodeBytes(image, CONFIG))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("no base64");
    }

    @Test
    void decode_bytes_throws_on_invalid_base64() {
        Image image = Image.builder().base64Data("@@not-base64@@").build();

        assertThatThrownBy(() -> OpenAiImageProvider.decodeBytes(image, CONFIG))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("decode");
    }

    @Test
    void write_to_destination_commits_bytes_and_metadata() {
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[] {9, 8, 7});
        Image image = Image.builder()
                .base64Data(b64)
                .mimeType("image/png")
                .revisedPrompt("A serene watercolor cat")
                .build();
        RecordingStream sink = new RecordingStream();

        OpenAiImageProvider.writeToDestination(image, CONFIG, 1234L, sink);

        assertThat(sink.mimeType).isEqualTo("image/png");
        assertThat(sink.bytes()).containsExactly(9, 8, 7);
        assertThat(sink.metadata)
                .containsEntry("model", "openai:gpt-image-1")
                .containsEntry("durationMs", "1234")
                .containsEntry("aspectRatio", "1:1")
                .containsEntry("revisedPrompt", "A serene watercolor cat");
        assertThat(sink.closed).isTrue();
    }

    @Test
    void write_to_destination_omits_revised_prompt_when_blank() {
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[] {1});
        Image image = Image.builder().base64Data(b64).build();
        RecordingStream sink = new RecordingStream();

        OpenAiImageProvider.writeToDestination(image, CONFIG, 100L, sink);

        assertThat(sink.metadata).doesNotContainKey("revisedPrompt");
    }

    @Test
    void write_to_destination_throws_on_null_image() {
        assertThatThrownBy(() -> OpenAiImageProvider.writeToDestination(
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
