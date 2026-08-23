package de.mhus.vance.addon.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Whose manifest is this? The config endpoints take a folder from the caller
 * and write {@code app: feeds} back unconditionally, so reading somebody else's
 * {@code _app.yaml} has to be refused on the way in — a wrong folder parameter
 * would otherwise remove another app from the kind registry with no error.
 */
class FeedsManifestIdentityTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private FeedsApplication application;

    @BeforeEach
    void setUp() {
        application = new FeedsApplication(
                documentService, mock(DocumentLinkBuilder.class),
                mock(SecurityContextFactory.class));
        DocumentDocument doc = new DocumentDocument();
        doc.setMimeType("application/yaml");
        when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(doc));
    }

    @Test
    void readManifest_refusesTheManifestOfAnotherApp() {
        when(documentService.readContent(any())).thenReturn("""
                $meta:
                  kind: application
                  app: workbook
                title: My book
                workbook:
                  pages: []
                """);

        assertThatThrownBy(() ->
                application.readConfig("acme", "news", "apps/mybook"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("workbook");
    }

    @Test
    void readManifest_acceptsAFeedsManifest() {
        when(documentService.readContent(any())).thenReturn("""
                $meta:
                  kind: application
                  app: feeds
                title: News
                feeds:
                  streams:
                  - source: hrafnagud
                """);

        assertThat(application.readConfig("acme", "news", "apps/feeds").streams()).hasSize(1);
    }

    @Test
    void normaliseFolder_refusesTraversal() {
        // The folder is authorised as "<folder>/_app.yaml"; a "..' in it would
        // make the authorised path and the written path two different things.
        assertThatThrownBy(() -> FeedsApplication.normaliseFolder("apps/../_vance/anything"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("..");
    }

    @Test
    void normaliseFolder_refusesAnEmptySegment() {
        assertThatThrownBy(() -> FeedsApplication.normaliseFolder("apps//feeds"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void normaliseFolder_stripsSurroundingSlashes() {
        assertThat(FeedsApplication.normaliseFolder("/apps/feeds/")).isEqualTo("apps/feeds");
    }
}
