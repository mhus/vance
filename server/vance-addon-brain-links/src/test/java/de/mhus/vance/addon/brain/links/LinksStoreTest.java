package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Whose manifest is this?
 *
 * <p>{@code saveConfig} writes {@code app: links} back unconditionally, so the
 * question has to be asked on the way in. A wrong {@code folder} — no attack
 * needed — would otherwise convert another app's manifest: its own block stays
 * behind as ballast, the kind lookup for it stops matching, and the caller is
 * told the entry was added.
 */
class LinksStoreTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "reading";

    private DocumentService documentService;
    private LinksStore store;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        store = new LinksStore(documentService, mock(SecurityContextFactory.class));
        DocumentDocument doc = new DocumentDocument();
        doc.setMimeType("application/yaml");
        doc.setPath("notes/mybook/_app.yaml");
        when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(doc));
    }

    @Test
    void load_refusesTheManifestOfAnotherApp() {
        when(documentService.readContent(any())).thenReturn("""
                $meta:
                  kind: application
                  app: workbook
                title: My book
                workbook:
                  pages: []
                """);

        assertThatThrownBy(() -> store.load(TENANT, PROJECT, "notes/mybook"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("workbook");
    }

    @Test
    void load_acceptsALinksManifest() {
        when(documentService.readContent(any())).thenReturn("""
                $meta:
                  kind: application
                  app: links
                title: Reading
                links:
                  entries:
                  - url: https://a.example/
                """);

        assertThat(store.load(TENANT, PROJECT, "notes/links").config().entries()).hasSize(1);
    }

    @Test
    void load_acceptsAManifestThatNamesNoApp() {
        // A hand-written manifest without $meta.app is under-specified, not
        // somebody else's — refusing it would make an existing list unusable.
        when(documentService.readContent(any())).thenReturn("""
                $meta:
                  kind: application
                title: Reading
                links:
                  entries: []
                """);

        assertThat(store.load(TENANT, PROJECT, "notes/links").config().entries()).isEmpty();
    }
}
