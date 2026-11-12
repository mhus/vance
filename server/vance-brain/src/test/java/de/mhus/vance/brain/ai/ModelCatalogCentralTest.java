package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.resources.BundledResourceResolver;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideDocument;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideFile;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideRef;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The bundled layer of the catalog from a central version instead of the classpath. */
class ModelCatalogCentralTest {

    private static final String GATEWAY_PROVIDER = "wireType: openai\ndisplayName: Gateway\n";
    private static final String GATEWAY_MODEL =
            "contextWindowTokens: 123456\ndefaultMaxOutputTokens: 4096\nsize: LARGE\n";

    private ResourceOverrideService service;
    private ModelCatalog catalog;

    @BeforeEach
    void setUp() {
        service = mock(ResourceOverrideService.class);
        when(service.effective("model")).thenReturn(Optional.empty());
        DocumentService documents = mock(DocumentService.class);
        when(documents.findAllByPathPrefix(ModelCatalog.MODEL_PATH_PREFIX)).thenReturn(List.of());
        when(documents.findAllByPathPrefix(ModelCatalog.AUTO_MODEL_PATH_PREFIX)).thenReturn(List.of());
        catalog = new ModelCatalog(
                documents,
                new ModelQuirks(),
                new BundledResourceResolver(service, true, List.of("model")),
                TenantModelSource.central());
    }

    private void publish(long version, boolean overwrite, List<ResourceOverrideFile> files) {
        when(service.effective("model"))
                .thenReturn(Optional.of(new ResourceOverrideRef("model", version, overwrite, "d")));
        when(service.find("model", version))
                .thenReturn(Optional.of(ResourceOverrideDocument.builder()
                        .type("model")
                        .version(version)
                        .enabled(true)
                        .overwrite(overwrite)
                        .files(files)
                        .build()));
    }

    @Test
    void noCentralVersion_servesTheClasspathCatalog() {
        assertThat(catalog.bundledOrigin()).isEqualTo("classpath");
        assertThat(catalog.lookup("anthropic", "claude-sonnet-4-5")).isPresent();
    }

    @Test
    void centralVersion_replacesTheBundledLayerCompletely() {
        publish(
                1,
                true,
                List.of(
                        new ResourceOverrideFile("gateway/_provider.yaml", GATEWAY_PROVIDER),
                        new ResourceOverrideFile("gateway/chat-1.yaml", GATEWAY_MODEL)));

        catalog.reload();

        assertThat(catalog.bundledOrigin()).isEqualTo("central v1");
        assertThat(catalog.lookup("gateway", "chat-1")).isPresent();
        assertThat(catalog.lookup("gateway", "chat-1").orElseThrow().contextWindowTokens())
                .isEqualTo(123_456);
        assertThat(catalog.lookup("anthropic", "claude-sonnet-4-5")).isEmpty();
    }

    @Test
    void switchedOff_returnsToTheClasspathCatalog() {
        publish(1, true, List.of(new ResourceOverrideFile("gateway/chat-1.yaml", GATEWAY_MODEL)));
        catalog.reload();
        publish(2, false, List.of());

        catalog.reload();

        assertThat(catalog.bundledOrigin()).isEqualTo("classpath");
        assertThat(catalog.lookup("gateway", "chat-1")).isEmpty();
        assertThat(catalog.lookup("anthropic", "claude-sonnet-4-5")).isPresent();
    }

    @Test
    void centralVersionWithoutAModel_keepsTheRunningCatalog() {
        publish(1, true, List.of(new ResourceOverrideFile("gateway/chat-1.yaml", GATEWAY_MODEL)));
        catalog.reload();
        publish(2, true, List.of(new ResourceOverrideFile("gateway/_provider.yaml", GATEWAY_PROVIDER)));

        catalog.reload();

        assertThat(catalog.bundledOrigin()).isEqualTo("central v1");
        assertThat(catalog.lookup("gateway", "chat-1")).isPresent();
    }

    @Test
    void unparsableFile_isSkippedWithoutLosingTheRest() {
        publish(
                1,
                true,
                List.of(
                        new ResourceOverrideFile("gateway/broken.yaml", "key: [unclosed"),
                        new ResourceOverrideFile("gateway/chat-1.yaml", GATEWAY_MODEL)));

        catalog.reload();

        assertThat(catalog.lookup("gateway", "chat-1")).isPresent();
    }
}
