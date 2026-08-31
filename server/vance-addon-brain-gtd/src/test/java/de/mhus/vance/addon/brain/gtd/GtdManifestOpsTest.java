package de.mhus.vance.addon.brain.gtd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import de.mhus.vance.toolpack.ToolException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The manifest write of §8b: what survives a reorder and what does not.
 *
 * <p>The interesting property is not that the new key appears — it is that
 * everything <em>else</em> in the manifest is still there afterwards. The
 * write is a full YAML round-trip, so a dropped key would be a silent
 * configuration loss on an ordinary drag.
 */
class GtdManifestOpsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 31);

    private final DocumentService documentService = mock(DocumentService.class);
    private final GtdFolderReader folderReader = mock(GtdFolderReader.class);
    private final GtdService gtdService = new GtdService(
            documentService, folderReader, new GtdBucketResolver(),
            mock(SecurityContextFactory.class));
    private final GtdManifestOps ops = new GtdManifestOps(
            documentService, folderReader, gtdService,
            mock(SecurityContextFactory.class));

    private static final String MANIFEST = """
            $meta:
              kind: application
              app: gtd
            title: "My Life"
            description: "Everything I owe somebody"
            gtd:
              inboxDir: in
              actionsDir: next
              projectsDir: areas
              contexts: ["@calls", "@home"]
              inboxOrder: ["keep-me"]
            """;

    private DocumentDocument manifestDoc(String body) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("manifest-id");
        doc.setPath("gtd/life/_app.yaml");
        doc.setTitle("My Life");
        doc.setTenantId("acme");
        doc.setMimeType("application/yaml");
        when(documentService.readContent(doc)).thenReturn(body);
        return doc;
    }

    private DocumentDocument action(String id, String title, boolean inInbox) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId(id);
        doc.setPath("gtd/life/next/" + id + ".md");
        doc.setTitle(title);
        return doc;
    }

    /** Two Today actions plus the manifest, wired into a scan of {@code gtd/life}. */
    private void givenScan(String body) {
        DocumentDocument manifest = manifestDoc(body);
        List<GtdAction> actions = List.of(
                new GtdAction(action("a", "Ask boss", false), "next/a.md", false, null,
                        "Ask boss", "today", null, List.of(), false),
                new GtdAction(action("b", "Buy milk", false), "next/b.md", false, null,
                        "Buy milk", "today", null, List.of(), false));
        when(folderReader.scan("acme", "proj", "gtd/life"))
                .thenReturn(new GtdFolderReader.Scan(
                        "gtd/life", manifest, GtdConfig.parse(body), actions));
    }

    /** The body handed to {@code DocumentService.update}, re-parsed. */
    private GtdConfig writtenConfig() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(documentService).update(eq("manifest-id"), anyString(), any(),
                body.capture(), any(), any(), any(), any(), anyString(), any(), any());
        return GtdConfig.parse(body.getValue());
    }

    @Test
    void reorderBucket_writesTheNewOrder() {
        givenScan(MANIFEST);
        ops.reorderBucket("acme", "proj", "gtd/life",
                GtdBucket.TODAY, List.of("b", "a"), TODAY, "mhus");
        assertThat(writtenConfig().bucketOrder().get(GtdBucket.TODAY))
                .containsExactly("b", "a");
    }

    @Test
    void reorderBucket_keepsEveryOtherManifestKey() {
        givenScan(MANIFEST);
        ops.reorderBucket("acme", "proj", "gtd/life",
                GtdBucket.TODAY, List.of("b", "a"), TODAY, "mhus");
        GtdConfig written = writtenConfig();
        assertThat(written.title()).isEqualTo("My Life");
        assertThat(written.description()).isEqualTo("Everything I owe somebody");
        assertThat(written.inboxDir()).isEqualTo("in");
        assertThat(written.actionsDir()).isEqualTo("next");
        assertThat(written.projectsDir()).isEqualTo("areas");
        assertThat(written.suggestedContexts()).containsExactly("@calls", "@home");
        // Another bucket's order is not this write's business.
        assertThat(written.bucketOrder().get(GtdBucket.INBOX)).containsExactly("keep-me");
    }

    @Test
    void reorderBucket_emptyBucket_removesTheKeyRatherThanWritingAnEmptyList() {
        givenScan(MANIFEST);
        // SOMEDAY holds nothing, so there is no order left to record.
        ops.reorderBucket("acme", "proj", "gtd/life",
                GtdBucket.SOMEDAY, List.of(), TODAY, "mhus");
        assertThat(writtenConfig().bucketOrder()).doesNotContainKey(GtdBucket.SOMEDAY);
    }

    @Test
    void reorderBucket_foreignApp_refusesInsteadOfConvertingTheManifest() {
        // The write puts `app: gtd` back unconditionally; without the guard a
        // reorder aimed at a workbook folder would quietly take it over.
        String foreign = """
                $meta:
                  kind: application
                  app: workbook
                title: "Notes"
                """;
        givenScan(foreign);
        assertThatThrownBy(() -> ops.reorderBucket("acme", "proj", "gtd/life",
                GtdBucket.TODAY, List.of("b", "a"), TODAY, "mhus"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("workbook");
        verify(documentService, never()).update(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }
}
