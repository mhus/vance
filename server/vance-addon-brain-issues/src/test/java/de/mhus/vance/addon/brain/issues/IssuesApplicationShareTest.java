package de.mhus.vance.addon.brain.issues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication.ShareIntake;
import de.mhus.vance.brain.applications.VanceApplication.ShareIntakeContext;
import de.mhus.vance.brain.applications.VanceApplication.ShareIntakeResult;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What the issue tracker does with a share: the label it reports back, and
 * the order in which it reads and writes.
 */
class IssuesApplicationShareTest {

    private final IssuesFolderReader folderReader = mock(IssuesFolderReader.class);
    private final IssuesService issuesService = mock(IssuesService.class);

    private final IssuesApplication app = new IssuesApplication(
            folderReader,
            mock(IssuesStatsBuilder.class),
            mock(IssuesRenderer.class),
            mock(DocumentService.class),
            mock(DocumentLinkBuilder.class),
            issuesService,
            mock(de.mhus.vance.brain.permission.SecurityContextFactory.class));

    @Test
    void acceptShare_manifestWithoutATitleField_usesTheDocumentTitle() {
        // `issues_app_create` without a title writes no `title:` line but does
        // set the document title, so the chooser says "Issues" while the YAML
        // field is empty. Reading only the field said "Added to fehler".
        givenApp("Issues", /* yamlTitle */ null);

        ShareIntakeResult result = app.acceptShare(shareOf("Crash on save", "look at this"));

        assertThat(result.created()).isTrue();
        assertThat(result.label()).isEqualTo("Issues");
    }

    @Test
    void acceptShare_yamlTitleWins_whenTheDocumentHasNone() {
        givenApp(null, "Fehlerliste");

        assertThat(app.acceptShare(shareOf("Crash on save", "look")).label())
                .isEqualTo("Fehlerliste");
    }

    @Test
    void acceptShare_neitherTitle_fallsBackToTheFolderName() {
        givenApp(null, null);

        assertThat(app.acceptShare(shareOf("Crash on save", "look")).label())
                .isEqualTo("fehler");
    }

    @Test
    void acceptShare_readsTheManifestBeforeWritingTheIssue() {
        // Order matters: scanning afterwards only for a label meant a manifest
        // that vanished in between threw *after* the issue existed. ToolException
        // is not one of the Share* kinds, so the user got HTTP 500, shared
        // again, and ended up with two issues.
        givenApp("Issues", null);

        app.acceptShare(shareOf("Crash on save", "look"));

        var order = inOrder(folderReader, issuesService);
        order.verify(folderReader).scan("acme", "proj", "fehler");
        order.verify(issuesService).createIssue(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptShare_missingManifest_failsWithoutCreatingAnIssue() {
        when(folderReader.scan("acme", "proj", "fehler"))
                .thenThrow(new ToolException("No issues manifest at 'fehler/_app.yaml'."));

        assertThatThrownBy(() -> app.acceptShare(shareOf("Crash on save", "look")))
                .isInstanceOf(ToolException.class);

        verify(issuesService, never()).createIssue(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptShare_titlelessSubject_opensTheIssueUnderAGenericName() {
        givenApp("Issues", null);

        app.acceptShare(shareOf(null, "look at this"));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(issuesService).createIssue(eq("acme"), eq("proj"), eq("fehler"),
                title.capture(), any(), any(), any(), any(), eq("mara"));
        assertThat(title.getValue()).isEqualTo("Shared item");
    }

    @Test
    void acceptShare_opensTheIssueWithTheAssembledBody() {
        givenApp("Issues", null);

        app.acceptShare(new ShareIntakeContext(
                "acme", "proj", "fehler",
                new ShareIntake("Crash on save", "https://example.com/hit", "the quote", false),
                "have a look", "mara"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(issuesService).createIssue(any(), any(), any(), any(), any(), any(), any(),
                body.capture(), any());
        assertThat(body.getValue())
                .contains("have a look")
                .contains("https://example.com/hit")
                .contains("> the quote");
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenApp(@Nullable String documentTitle, @Nullable String yamlTitle) {
        DocumentDocument manifest = new DocumentDocument();
        manifest.setPath("fehler/_app.yaml");
        manifest.setTitle(documentTitle);
        IssuesConfig config = new IssuesConfig(
                yamlTitle, null, IssuesConfig.DEFAULT_ITEMS_DIR,
                IssuesConfig.DEFAULT_ARCHIVE_DIR, 1, new ArrayList<>());
        when(folderReader.scan("acme", "proj", "fehler"))
                .thenReturn(new IssuesFolderReader.Scan("fehler", manifest, config, List.of()));
    }

    private static ShareIntakeContext shareOf(@Nullable String title, String note) {
        return new ShareIntakeContext(
                "acme", "proj", "fehler",
                new ShareIntake(title, "https://example.com/hit", null, false),
                note, "mara");
    }
}
