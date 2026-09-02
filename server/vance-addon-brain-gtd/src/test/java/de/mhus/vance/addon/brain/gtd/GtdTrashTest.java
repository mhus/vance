package de.mhus.vance.addon.brain.gtd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The trash bucket and the two-step completion around it.
 *
 * <p>What is pinned here is the pair of properties the feature exists for:
 * ticking a box changes nothing but the flag, and nothing an action carries is
 * quietly lost on the way into the bin and back out — a project membership
 * survives the round trip, which is the only reason the origin is recorded at
 * all.
 */
class GtdTrashTest {

    private static final String ROOT = "gtd/life";
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 31);

    private final DocumentService documentService = mock(DocumentService.class);
    private final GtdFolderReader folderReader = mock(GtdFolderReader.class);
    private final GtdService service = new GtdService(
            documentService, folderReader, new GtdBucketResolver(),
            mock(SecurityContextFactory.class));

    // ── move into the trash ───────────────────────────────────────

    @Test
    void trashing_relocatesIntoTrashDir_andRemembersTheProjectFolder() {
        givenAction("gtd/life/projects/relaunch/brief.md", """
                ---
                kind: action
                title: Brief schreiben
                when: today
                ---
                """);

        service.move("acme", "proj", ROOT, GtdConfig.defaults(),
                "gtd/life/projects/relaunch/brief.md", GtdBucket.TRASH, null, "mara");

        assertThat(writtenPath()).isEqualTo("gtd/life/trash/brief.md");
        assertThat(writtenBody())
                .contains("trashedFrom: projects/relaunch")
                // Putting something away must not rewrite when it was due —
                // otherwise dragging it back out lands it somewhere it never was.
                .contains("when: today");
    }

    @Test
    void trashingOutOfTheInbox_remembersNothing() {
        givenAction("gtd/life/inbox/call-tax.md", """
                ---
                kind: action
                title: Call tax office
                ---
                """);

        service.move("acme", "proj", ROOT, GtdConfig.defaults(),
                "gtd/life/inbox/call-tax.md", GtdBucket.TRASH, null, "mara");

        // Restoring an unprocessed action means processing it, and a processed
        // action belongs in actions/ — the same thing leaving the inbox does.
        assertThat(writtenBody()).doesNotContain("trashedFrom");
    }

    // ── move back out ─────────────────────────────────────────────

    @Test
    void restoring_putsItBackWhereItCameFrom_andForgetsTheOrigin() {
        givenAction("gtd/life/trash/brief.md", """
                ---
                kind: action
                title: Brief schreiben
                when: today
                trashedFrom: projects/relaunch
                ---
                """);

        service.move("acme", "proj", ROOT, GtdConfig.defaults(),
                "gtd/life/trash/brief.md", GtdBucket.ANYTIME, null, "mara");

        assertThat(writtenPath()).isEqualTo("gtd/life/projects/relaunch/brief.md");
        assertThat(writtenBody()).doesNotContain("trashedFrom");
    }

    @Test
    void restoring_withoutARememberedOrigin_landsInActions() {
        givenAction("gtd/life/trash/brief.md", """
                ---
                kind: action
                title: Brief schreiben
                ---
                """);

        service.move("acme", "proj", ROOT, GtdConfig.defaults(),
                "gtd/life/trash/brief.md", GtdBucket.TODAY, null, "mara");

        assertThat(writtenPath()).isEqualTo("gtd/life/actions/brief.md");
    }

    @Test
    void restoring_ignoresAHandEditedOriginThatWouldEscapeTheRoot() {
        givenAction("gtd/life/trash/brief.md", """
                ---
                kind: action
                title: Brief schreiben
                trashedFrom: ../../../etc
                ---
                """);

        service.move("acme", "proj", ROOT, GtdConfig.defaults(),
                "gtd/life/trash/brief.md", GtdBucket.TODAY, null, "mara");

        assertThat(writtenPath()).isEqualTo("gtd/life/actions/brief.md");
    }

    // ── delete ────────────────────────────────────────────────────

    @Test
    void deleteOutsideTheTrash_movesItThere_andRemovesNothing() {
        givenAction("gtd/life/actions/brief.md", """
                ---
                kind: action
                title: Brief schreiben
                ---
                """);

        GtdService.DeleteOutcome outcome = service.deleteAction("acme", "proj", ROOT,
                GtdConfig.defaults(), "gtd/life/actions/brief.md", "mara");

        assertThat(outcome).isEqualTo(GtdService.DeleteOutcome.TRASHED);
        assertThat(writtenPath()).isEqualTo("gtd/life/trash/brief.md");
        verify(documentService, never()).trash(anyString(), any());
    }

    @Test
    void deleteInsideTheTrash_handsTheDocumentToTheProjectSoftDelete() {
        givenAction("gtd/life/trash/brief.md", """
                ---
                kind: action
                title: Brief schreiben
                ---
                """);

        GtdService.DeleteOutcome outcome = service.deleteAction("acme", "proj", ROOT,
                GtdConfig.defaults(), "gtd/life/trash/brief.md", "mara");

        assertThat(outcome).isEqualTo(GtdService.DeleteOutcome.PURGED);
        verify(documentService).trash(eq("doc-id"), any());
    }

    // ── sweep + bucketing ─────────────────────────────────────────

    @Test
    void sweep_movesCompletedActions_andLeavesOpenOnesAlone() {
        GtdAction done = action("gtd/life/actions/done.md", true, false);
        GtdAction open = action("gtd/life/actions/open.md", false, false);
        GtdAction alreadyGone = action("gtd/life/trash/old.md", true, true);
        givenAction("gtd/life/actions/done.md", """
                ---
                kind: action
                title: Done thing
                done: true
                ---
                """);

        int moved = service.sweepDoneToTrash("acme", "proj", ROOT, GtdConfig.defaults(),
                scanOf(done, open, alreadyGone), "mara");

        assertThat(moved).isEqualTo(1);
        assertThat(writtenPath()).isEqualTo("gtd/life/trash/done.md");
    }

    @Test
    void completedActions_keepTheirBucket_untilSomebodySweeps() {
        GtdAction done = action("gtd/life/actions/done.md", true, false);
        GtdAction trashed = action("gtd/life/trash/old.md", true, true);

        var buckets = service.computeBuckets(scanOf(done, trashed), TODAY);

        // The line stays where the person ticked it — that is the whole point.
        assertThat(buckets.get(GtdBucket.ANYTIME)).containsExactly(done);
        assertThat(buckets.get(GtdBucket.TRASH)).containsExactly(trashed);
    }

    // ── helpers ───────────────────────────────────────────────────

    private void givenAction(String path, String body) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-id");
        doc.setPath(path);
        doc.setTenantId("acme");
        doc.setMimeType("text/markdown");
        when(documentService.findByPath("acme", "proj", path)).thenReturn(Optional.of(doc));
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        // Every other path is free, so uniquePath takes the first candidate.
        when(documentService.update(anyString(), any(), any(), anyString(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(doc);
    }

    private static GtdAction action(String path, boolean done, boolean inTrash) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId(path);
        doc.setPath(path);
        return new GtdAction(doc, path.substring(ROOT.length() + 1), false, inTrash, null,
                "Thing", "", null, List.of(), done);
    }

    private GtdFolderReader.Scan scanOf(GtdAction... actions) {
        DocumentDocument manifest = new DocumentDocument();
        manifest.setPath(ROOT + "/_app.yaml");
        return new GtdFolderReader.Scan(ROOT, manifest, GtdConfig.defaults(),
                new ArrayList<>(List.of(actions)));
    }

    private String writtenPath() {
        return capturedUpdate().getAllValues().get(3);
    }

    private String writtenBody() {
        return capturedUpdate().getAllValues().get(2);
    }

    /**
     * Positional capture of the update funnel. Four of its String arguments are
     * captured — id, title, inline text, new path — so the captured values come
     * back in that order, whatever the argument positions are.
     */
    private ArgumentCaptor<String> capturedUpdate() {
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(documentService).update(args.capture(), args.capture(), any(), args.capture(),
                args.capture(), any(), any(), any(), anyString(), any(), any());
        return args;
    }
}
