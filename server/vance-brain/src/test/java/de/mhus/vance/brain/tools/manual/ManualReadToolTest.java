package de.mhus.vance.brain.tools.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Resilience-focused — the LLM frequently echoes a manual path back
 * verbatim from a workspace listing or an error message ("manuals/
 * essay/STYLE.md", "essay/STYLE.md", "/STYLE.md"). All of these
 * shapes must resolve to the same content.
 */
class ManualReadToolTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test-project";
    private static final String SESSION = "sess1";
    private static final String PROCESS = "proc-1";
    private static final String FOLDER = "manuals/essay/";
    private static final String STEM = "STYLE";

    private DocumentService documentService;
    private ThinkProcessService thinkProcessService;
    private ManualReadTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        tool = new ManualReadTool(documentService, thinkProcessService);
        ctx = new ToolInvocationContext(TENANT, PROJECT, SESSION, PROCESS, "user-1");

        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId(PROCESS);
        process.setTenantId(TENANT);
        process.setProjectId(PROJECT);
        process.setSessionId(SESSION);
        process.setEngineParams(Map.of("manualPaths", List.of(FOLDER)));
        when(thinkProcessService.findById(PROCESS)).thenReturn(Optional.of(process));

        LookupResult result = mock(LookupResult.class);
        when(result.content()).thenReturn("# Style guide\n");
        when(result.source()).thenReturn(LookupResult.Source.PROJECT);
        when(documentService.lookupCascade(TENANT, PROJECT, FOLDER + STEM + ".md"))
                .thenReturn(Optional.of(result));
    }

    @Test
    void bareStemResolves() {
        Map<String, Object> out = tool.invoke(Map.of("name", "STYLE"), ctx);
        assertThat(out).containsEntry("name", "STYLE")
                .containsEntry("folder", FOLDER);
    }

    @Test
    void stemWithMdSuffixResolves() {
        Map<String, Object> out = tool.invoke(Map.of("name", "STYLE.md"), ctx);
        assertThat(out).containsEntry("name", "STYLE");
    }

    @Test
    void fullFolderPathResolves() {
        Map<String, Object> out = tool.invoke(Map.of("name", "manuals/essay/STYLE.md"), ctx);
        assertThat(out).containsEntry("name", "STYLE");
    }

    @Test
    void partialFolderPathResolves() {
        // The LLM echoes "essay/STYLE.md" from a workspace listing —
        // only the last segment is the actual manual stem.
        Map<String, Object> out = tool.invoke(Map.of("name", "essay/STYLE.md"), ctx);
        assertThat(out).containsEntry("name", "STYLE");
    }

    @Test
    void leadingSlashIsTolerated() {
        Map<String, Object> out = tool.invoke(Map.of("name", "/manuals/essay/STYLE.md"), ctx);
        assertThat(out).containsEntry("name", "STYLE");
    }

    @Test
    void backslashesAreNormalised() {
        Map<String, Object> out = tool.invoke(
                Map.of("name", "manuals\\essay\\STYLE.md"), ctx);
        assertThat(out).containsEntry("name", "STYLE");
    }

    @Test
    void pathTraversalRejected() {
        assertThatThrownBy(() -> tool.invoke(Map.of("name", "../etc/passwd"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Invalid manual name");
    }

    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> tool.invoke(Map.of("name", "   "), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'name' is required");
    }

    @Test
    void unknownNameStillFailsWithUsefulHint() {
        assertThatThrownBy(() -> tool.invoke(Map.of("name", "DOES-NOT-EXIST"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Manual not found")
                .hasMessageContaining("manual_list");
    }
}
