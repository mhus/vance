package de.mhus.vance.brain.tools.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.documents.MountSearchOutcome;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.jaglan.JaglanShellService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code mount_search} must not stay silent about a mount it never asked.
 *
 * <p>The tool's own description promises that mounts without search show up in
 * {@code notSearched}, but the list was built from {@code statusText} alone —
 * which is set only when a <em>capabilities</em> fetch failed recently. A
 * source that simply cannot search has no status text, so it vanished from the
 * answer entirely: not in the results, not in {@code notSearched}. An agent
 * then reads "0 results" as "the file is not there", which is the one
 * conclusion the whole mount surface exists to prevent.
 */
class MountSearchToolTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private KindToolSupport support;
    private DocumentService documentService;
    private JaglanShellService shellService;
    private MountSearchTool tool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        support = mock(KindToolSupport.class);
        documentService = mock(DocumentService.class);
        shellService = mock(JaglanShellService.class);
        EddieContext eddieContext = mock(EddieContext.class);

        when(support.documentService()).thenReturn(documentService);
        when(support.eddieContext()).thenReturn(eddieContext);
        when(eddieContext.resolveProject(any(), any(), anyBoolean()))
                .thenReturn(ProjectDocument.builder().name(PROJECT).build());

        ObjectProvider<JaglanShellService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(shellService);
        tool = new MountSearchTool(support, provider);
    }

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext(TENANT, PROJECT, null, "proc-1", "alice");
    }

    private static MountedSource source(String name, boolean canSearch, String statusText) {
        return new MountedSource(name, null, "ode", MountAccess.RO, null, statusText,
                Duration.ofMinutes(5), canSearch);
    }

    private void mounts(MountedSource... sources) {
        when(documentService.listMounts(TENANT, PROJECT)).thenReturn(List.of(sources));
    }

    private static DocumentDocument hit(String path) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT)
                .path(path).name("dune.pdf").title("Dune")
                .mimeType("application/pdf").size(12)
                .build();
        doc.setId("ext_x");
        return doc;
    }

    private void answers(String mount, JaglanShellService.MountSearch answer) {
        when(shellService.searchInMount(eq(TENANT), eq(PROJECT), eq(mount), anyString(), anyInt()))
                .thenReturn(answer);
    }

    private static JaglanShellService.MountSearch delegated(DocumentDocument... hits) {
        return new JaglanShellService.MountSearch(List.of(hits), MountSearchOutcome.DELEGATED);
    }

    private static JaglanShellService.MountSearch outcome(MountSearchOutcome outcome) {
        return new JaglanShellService.MountSearch(List.of(), outcome);
    }

    @SuppressWarnings("unchecked")
    private static List<String> notSearched(Map<String, Object> out) {
        return (List<String>) out.get("notSearched");
    }

    @Test
    void search_mountThatCannotSearch_isNamedNotOmitted() {
        mounts(source("library", true, null), source("archive", false, null));
        answers("library", delegated(hit("_ext/library/books/dune.pdf")));
        answers("archive", outcome(MountSearchOutcome.UNSUPPORTED));

        Map<String, Object> out = tool.invoke(Map.of("query", "dune"), ctx());

        assertThat(out.get("count")).isEqualTo(1);
        assertThat(notSearched(out)).hasSize(1);
        assertThat(notSearched(out).get(0))
                .contains("archive")
                .contains("does not support search");
    }

    @Test
    void search_unreachableMount_isNamedWithItsStatus() {
        mounts(source("library", true, "source did not answer"));
        answers("library", outcome(MountSearchOutcome.UNAVAILABLE));

        Map<String, Object> out = tool.invoke(Map.of("query", "dune"), ctx());

        assertThat(out.get("count")).isEqualTo(0);
        assertThat(notSearched(out).get(0))
                .contains("library")
                .contains("source did not answer");
    }

    @Test
    void search_everyMountAnswered_reportsNoNotSearchedList() {
        mounts(source("library", true, null));
        answers("library", delegated(hit("_ext/library/books/dune.pdf")));

        Map<String, Object> out = tool.invoke(Map.of("query", "dune"), ctx());

        assertThat(out).doesNotContainKey("notSearched");
        assertThat(out.get("results")).isInstanceOf(List.class);
    }

    @Test
    void search_restrictedToOneMount_neverAsksTheOthers() {
        mounts(source("library", true, null), source("archive", true, null));
        answers("archive", delegated());

        Map<String, Object> out = tool.invoke(
                Map.of("query", "dune", "mount", "archive"), ctx());

        verify(shellService, never())
                .searchInMount(anyString(), anyString(), eq("library"), anyString(), anyInt());
        // And the mount that was deliberately excluded is not reported as a gap.
        assertThat(out).doesNotContainKey("notSearched");
    }

    @Test
    void search_limitExhausted_saysTheRemainingMountWasNeverAsked() {
        mounts(source("a", true, null), source("b", true, null));
        answers("a", delegated(hit("_ext/a/1.pdf")));

        Map<String, Object> out = tool.invoke(Map.of("query", "dune", "limit", 1), ctx());

        verify(shellService, never())
                .searchInMount(anyString(), anyString(), eq("b"), anyString(), anyInt());
        assertThat(notSearched(out).get(0)).contains("b").contains("limit");
    }

    @Test
    void search_projectWithoutMounts_saysSoAndAsksNobody() {
        mounts();

        Map<String, Object> out = tool.invoke(Map.of("query", "dune"), ctx());

        assertThat(out.get("count")).isEqualTo(0);
        assertThat(out.get("hint").toString()).contains("no mounted external sources");
        verify(shellService, never())
                .searchInMount(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void search_unknownMountName_isAnErrorNotAnEmptyResult() {
        mounts(source("library", true, null));

        Map<String, Object> out = tool.invoke(
                Map.of("query", "dune", "mount", "libary"), ctx());

        assertThat(out.get("error").toString()).contains("libary");
        assertThat(out.get("mounts")).isEqualTo(List.of("library"));
    }

    @Test
    void search_blankQuery_isRefusedBeforeAnyMountIsTouched() {
        Map<String, Object> out = tool.invoke(Map.of("query", "   "), ctx());

        assertThat(out.get("error").toString()).contains("query");
        verify(documentService, never()).listMounts(anyString(), anyString());
    }
}
