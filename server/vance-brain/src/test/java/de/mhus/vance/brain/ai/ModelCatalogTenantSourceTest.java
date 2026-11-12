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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A tenant can opt back to the classpath catalog while a central one is in force. */
class ModelCatalogTenantSourceTest {

    private final Set<String> classpathTenants = new HashSet<>();
    private ModelCatalog catalog;

    @BeforeEach
    void setUp() {
        ResourceOverrideService service = mock(ResourceOverrideService.class);
        when(service.effective("model")).thenReturn(Optional.of(new ResourceOverrideRef("model", 1, true, "d")));
        when(service.find("model", 1))
                .thenReturn(Optional.of(ResourceOverrideDocument.builder()
                        .type("model")
                        .version(1)
                        .enabled(true)
                        .overwrite(true)
                        .files(List.of(new ResourceOverrideFile("gateway/chat-1.yaml", "size: LARGE\n")))
                        .build()));
        DocumentService documents = mock(DocumentService.class);
        when(documents.findAllByPathPrefix(ModelCatalog.MODEL_PATH_PREFIX)).thenReturn(List.of());
        when(documents.findAllByPathPrefix(ModelCatalog.AUTO_MODEL_PATH_PREFIX)).thenReturn(List.of());
        catalog = new ModelCatalog(
                documents,
                new ModelQuirks(),
                new BundledResourceResolver(service, true, List.of("model")),
                classpathTenants::contains);
    }

    @Test
    void aTenantFollowsTheCentralCatalogByDefault() {
        assertThat(catalog.lookup("acme", null, "gateway", "chat-1")).isPresent();
        assertThat(catalog.lookup("acme", null, "anthropic", "claude-sonnet-4-5"))
                .isEmpty();
    }

    @Test
    void aTenantThatOptedBackSeesTheClasspathCatalogOnly() {
        classpathTenants.add("_vance");

        assertThat(catalog.lookup("_vance", null, "anthropic", "claude-sonnet-4-5"))
                .isPresent();
        assertThat(catalog.lookup("_vance", null, "gateway", "chat-1")).isEmpty();
        // The others are not affected.
        assertThat(catalog.lookup("acme", null, "gateway", "chat-1")).isPresent();
    }

    @Test
    void theChoiceIsReadAtLookupTime_notFrozenIntoTheSnapshot() {
        assertThat(catalog.lookup("acme", null, "gateway", "chat-1")).isPresent();

        classpathTenants.add("acme");

        assertThat(catalog.lookup("acme", null, "gateway", "chat-1")).isEmpty();
        assertThat(catalog.lookup("acme", null, "anthropic", "claude-sonnet-4-5"))
                .isPresent();
    }
}
