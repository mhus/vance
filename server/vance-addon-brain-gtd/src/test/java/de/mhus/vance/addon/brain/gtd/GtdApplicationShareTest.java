package de.mhus.vance.addon.brain.gtd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication.ShareIntake;
import de.mhus.vance.brain.applications.VanceApplication.ShareIntakeContext;
import de.mhus.vance.brain.applications.VanceApplication.ShareIntakeResult;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What the GTD app does with a share.
 *
 * <p>The one thing worth pinning here is the <b>label</b>: the message the
 * user reads afterwards has to name the app the way the chooser did. The
 * chooser shows the starred item's title, which falls back to the manifest
 * <em>document</em>'s title — and {@code gtd_app_create} without a title
 * writes no {@code title:} line into the YAML while setting the document
 * title to "GTD". Reading only the YAML field produced "Added to
 * &lt;folder&gt;" under an entry that said "GTD".
 */
class GtdApplicationShareTest {

    private final GtdFolderReader folderReader = mock(GtdFolderReader.class);
    private final GtdService gtdService = mock(GtdService.class);

    private final GtdApplication app = new GtdApplication(
            folderReader,
            gtdService,
            mock(GtdStatsBuilder.class),
            mock(GtdRenderer.class),
            mock(DocumentService.class),
            mock(DocumentLinkBuilder.class),
            mock(de.mhus.vance.brain.permission.SecurityContextFactory.class));

    @Test
    void acceptShare_manifestWithoutATitleField_usesTheDocumentTitle() {
        givenApp("Aufgaben", /* yamlTitle */ null);

        ShareIntakeResult result = app.acceptShare(shareOf("Canyon results", "look at this"));

        assertThat(result.created()).isTrue();
        assertThat(result.label()).isEqualTo("Aufgaben");
    }

    @Test
    void acceptShare_yamlTitleWins_whenTheDocumentHasNone() {
        givenApp(null, "Meine Tasks");

        assertThat(app.acceptShare(shareOf("Canyon results", "look")).label())
                .isEqualTo("Meine Tasks");
    }

    @Test
    void acceptShare_neitherTitle_fallsBackToTheFolderName() {
        givenApp(null, null);

        assertThat(app.acceptShare(shareOf("Canyon results", "look")).label())
                .isEqualTo("fehler");
    }

    @Test
    void acceptShare_titlelessSubject_capturesUnderAGenericName() {
        givenApp("Aufgaben", null);

        app.acceptShare(shareOf(null, "look at this"));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(gtdService).capture(eq("acme"), eq("proj"), eq("fehler"), any(),
                title.capture(), any(), eq("mara"));
        assertThat(title.getValue()).isEqualTo("Shared item");
    }

    @Test
    void acceptShare_capturesTheAssembledBody_notJustTheRemark() {
        givenApp("Aufgaben", null);

        app.acceptShare(new ShareIntakeContext(
                "acme", "proj", "fehler",
                new ShareIntake("Canyon results", "https://example.com/hit", "the quote", false),
                "have a look", "mara"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gtdService).capture(any(), any(), any(), any(), any(), body.capture(), any());
        assertThat(body.getValue())
                .contains("have a look")
                .contains("https://example.com/hit")
                .contains("> the quote");
    }

    @Test
    void acceptsShare_takesAnythingBecauseTheInboxIsForUnshapedThings() {
        assertThat(app.acceptsShare(new ShareIntake(null, null, "just a quote", false))).isTrue();
        assertThat(app.acceptsShare(new ShareIntake(null, null, null, true))).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenApp(@Nullable String documentTitle, @Nullable String yamlTitle) {
        DocumentDocument manifest = new DocumentDocument();
        manifest.setPath("fehler/_app.yaml");
        manifest.setTitle(documentTitle);
        GtdConfig config = new GtdConfig(
                yamlTitle, null, GtdConfig.DEFAULT_INBOX_DIR, GtdConfig.DEFAULT_ACTIONS_DIR,
                GtdConfig.DEFAULT_PROJECTS_DIR, new ArrayList<>());
        when(folderReader.scan("acme", "proj", "fehler"))
                .thenReturn(new GtdFolderReader.Scan("fehler", manifest, config, List.of()));
    }

    private static ShareIntakeContext shareOf(@Nullable String title, String note) {
        return new ShareIntakeContext(
                "acme", "proj", "fehler",
                new ShareIntake(title, "https://example.com/hit", null, false),
                note, "mara");
    }
}
