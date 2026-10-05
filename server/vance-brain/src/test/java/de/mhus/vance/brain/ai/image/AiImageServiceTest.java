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
        return new AiImageConfig(wire, wire, "gpt-image-1", "secret-key", null, "1:1", 60);
    }

    @Test
    void dispatch_routes_to_matching_provider() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AiImageModelProvider openai = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public void generate(AiImageConfig config, String prompt, ImageDestinationStream destination) {
                capturedPrompt.set(prompt);
            }
        };
        AiImageService service = new AiImageService(List.of(openai));
        service.postConstruct();

        service.generate(sampleConfig("openai"), "a watercolor cat", new DiscardingStream());

        assertThat(capturedPrompt.get()).isEqualTo("a watercolor cat");
    }

    @Test
    void missing_provider_throws_AiImageException() {
        AiImageService service = new AiImageService(List.of());
        service.postConstruct();

        assertThatThrownBy(() -> service.generate(sampleConfig("openai"), "anything", new DiscardingStream()))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("openai")
                .hasMessageContaining("registered");
    }

    @Test
    void duplicate_provider_type_fails_at_setup() {
        AiImageModelProvider a = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
        };
        AiImageModelProvider b = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
        };
        AiImageService service = new AiImageService(List.of(a, b));

        assertThatThrownBy(service::postConstruct)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate AiImageModelProvider");
    }

    @Test
    void hasProvider_typed_and_wire_name_lookup() {
        AiImageModelProvider gemini = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.GEMINI;
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
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

    @Test
    void instance_provider_wins_over_protocol_provider() {
        // The OpenRouter shape: a dedicated adapter claims the
        // `openrouter` instance while the stock OpenAI adapter serves
        // every other openai-wire instance (cortecs, vLLM, OpenAI
        // proper).
        java.util.concurrent.atomic.AtomicReference<String> protocolSink =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> instanceSink =
                new java.util.concurrent.atomic.AtomicReference<>();
        AiImageModelProvider stock = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {
                protocolSink.set("stock");
            }
        };
        AiImageModelProvider dedicated = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public java.util.Optional<String> getInstanceName() {
                return java.util.Optional.of("openrouter");
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {
                instanceSink.set("dedicated");
            }
        };
        AiImageService service = new AiImageService(List.of(stock, dedicated));
        service.postConstruct();

        // openrouter instance → dedicated adapter.
        service.generate(
                new AiImageConfig("openai", "openrouter", "seedream-5-0-flash", "key", null, "1:1", 60),
                "prompt",
                new DiscardingStream());
        assertThat(instanceSink.get()).isEqualTo("dedicated");
        assertThat(protocolSink.get())
                .as("stock adapter must not serve the claimed instance")
                .isNull();

        // openai protocol, unclaimed instance (e.g. cortecs) → stock adapter.
        service.generate(
                new AiImageConfig("openai", "cortecs", "flux-2-klein-4b", "key", null, "1:1", 60),
                "prompt",
                new DiscardingStream());
        assertThat(protocolSink.get()).isEqualTo("stock");
    }

    @Test
    void edit_fails_closed_when_provider_does_not_override() {
        AiImageModelProvider stock = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
            // no edit() override — the default must fail closed
        };
        AiImageService service = new AiImageService(List.of(stock));
        service.postConstruct();

        java.util.List<de.mhus.vance.brain.ai.image.ImageReference> refs =
                java.util.List.of(new de.mhus.vance.brain.ai.image.ImageReference(
                        "doc-1", "image/png", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.edit(
                        new AiImageConfig("openai", "openai", "gpt-image-1", "key", null, "1:1", 60),
                        "prompt",
                        refs,
                        new DiscardingStream()))
                .isInstanceOf(AiImageException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void edit_dispatches_to_instance_provider() {
        java.util.concurrent.atomic.AtomicReference<String> editSink =
                new java.util.concurrent.atomic.AtomicReference<>();
        AiImageModelProvider dedicated = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public java.util.Optional<String> getInstanceName() {
                return java.util.Optional.of("openrouter");
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}

            @Override
            public void edit(
                    AiImageConfig c,
                    String p,
                    java.util.List<de.mhus.vance.brain.ai.image.ImageReference> refs,
                    ImageDestinationStream d) {
                editSink.set("edited:" + refs.size());
            }
        };
        AiImageService service = new AiImageService(List.of(dedicated));
        service.postConstruct();

        java.util.List<de.mhus.vance.brain.ai.image.ImageReference> refs =
                java.util.List.of(new de.mhus.vance.brain.ai.image.ImageReference(
                        "doc-1", "image/png", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));

        service.edit(
                new AiImageConfig("openai", "openrouter", "seedream-5-0-flash", "key", null, "1:1", 60),
                "prompt",
                refs,
                new DiscardingStream());

        assertThat(editSink.get()).isEqualTo("edited:1");
    }

    @Test
    void duplicate_instance_provider_fails_at_setup() {
        AiImageModelProvider a = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public java.util.Optional<String> getInstanceName() {
                return java.util.Optional.of("openrouter");
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
        };
        AiImageModelProvider b = new AiImageModelProvider() {
            @Override
            public ProviderType getType() {
                return ProviderType.OPENAI;
            }

            @Override
            public java.util.Optional<String> getInstanceName() {
                return java.util.Optional.of("openrouter");
            }

            @Override
            public void generate(AiImageConfig c, String p, ImageDestinationStream d) {}
        };
        AiImageService service = new AiImageService(List.of(a, b));

        org.assertj.core.api.Assertions.assertThatThrownBy(service::postConstruct)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate AiImageModelProvider instance");
    }

    /** Sink stream that discards bytes — used when the test only cares
     *  about the dispatch path, not the image content. */
    private static final class DiscardingStream extends ImageDestinationStream {
        @Override
        public void write(int b) {}

        @Override
        public void write(byte[] b, int off, int len) {}

        @Override
        public void setMimeType(String mimeType) {}

        @Override
        public void setTitle(String title) {}

        @Override
        public void setMetadata(String key, String value) {}

        @Override
        public void setAltText(String altText) {}

        @Override
        public void close() {}
    }
}
