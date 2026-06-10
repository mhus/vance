package de.mhus.vance.brain.ai.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.ImageDestinationStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiImageServiceTest {

    private static AiImageConfig sampleConfig(String wire) {
        return new AiImageConfig(
                wire, wire, "gpt-image-1", "secret-key",
                null, "1:1", 60);
    }

    @Test
    void dispatch_routes_to_matching_provider() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AiImageModelProvider openai = new AiImageModelProvider() {
            @Override public ProviderType getType() { return ProviderType.OPENAI; }
            @Override
            public void generate(AiImageConfig config, String prompt,
                                 ImageDestinationStream destination) {
                capturedPrompt.set(prompt);
            }
        };
        AiImageService service = new AiImageService(List.of(openai));
        service.postConstruct();

        service.generate(sampleConfig("openai"), "a watercolor cat",
                new DiscardingStream());

        assertThat(capturedPrompt.get()).isEqualTo("a watercolor cat");
    }

    @Test
    void missing_provider_throws_AiImageException() {
        AiImageService service = new AiImageService(List.of());
        service.postConstruct();

        assertThatThrownBy(() -> service.generate(
                sampleConfig("openai"), "anything", new DiscardingStream()))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("OPENAI");
    }

    @Test
    void duplicate_provider_type_fails_at_setup() {
        AiImageModelProvider a = new AiImageModelProvider() {
            @Override public ProviderType getType() { return ProviderType.OPENAI; }
            @Override public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
        };
        AiImageModelProvider b = new AiImageModelProvider() {
            @Override public ProviderType getType() { return ProviderType.OPENAI; }
            @Override public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
        };
        AiImageService service = new AiImageService(List.of(a, b));

        assertThatThrownBy(service::postConstruct)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate AiImageModelProvider");
    }

    @Test
    void hasProvider_typed_and_wire_name_lookup() {
        AiImageModelProvider gemini = new AiImageModelProvider() {
            @Override public ProviderType getType() { return ProviderType.GEMINI; }
            @Override public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
        };
        AiImageService service = new AiImageService(List.of(gemini));
        service.postConstruct();

        assertThat(service.hasProvider(ProviderType.GEMINI)).isTrue();
        assertThat(service.hasProvider(ProviderType.OPENAI)).isFalse();
        assertThat(service.hasProvider("gemini")).isTrue();
        assertThat(service.hasProvider("openai")).isFalse();
        assertThat(service.hasProvider("not-a-provider")).isFalse();
        assertThat(service.listProviders()).containsExactly("gemini");
    }

    /** Sink stream that discards bytes — used when the test only cares
     *  about the dispatch path, not the image content. */
    private static final class DiscardingStream extends ImageDestinationStream {
        @Override public void write(int b) {}
        @Override public void write(byte[] b, int off, int len) {}
        @Override public void setMimeType(String mimeType) {}
        @Override public void setTitle(String title) {}
        @Override public void setMetadata(String key, String value) {}
        @Override public void setAltText(String altText) {}
        @Override public void close() {}
    }
}
