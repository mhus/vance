package de.mhus.vance.brain.tools.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
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
    private SkillResolver skillResolver;
    private SessionService sessionService;
    private ManualReadTool tool;
    private ToolInvocationContext ctx;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        skillResolver = mock(SkillResolver.class);
        sessionService = mock(SessionService.class);
        tool = new ManualReadTool(documentService, thinkProcessService,
                skillResolver, sessionService);
        ctx = new ToolInvocationContext(TENANT, PROJECT, SESSION, PROCESS, "user-1");

        process = new ThinkProcessDocument();
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

    @Test
    void activeSkillContributesItsOwnManualPaths() {
        // Recipe folder doesn't contain MANUAL — but the active skill's
        // own manualPath does. The tool must find it via the skill
        // contribution.
        String skillFolder = "manuals/decision/";
        process.setActiveSkills(List.of(
                ActiveSkillRefEmbedded.builder().name("decision-frame").build()));

        SessionDocument session = new SessionDocument();
        session.setSessionId(SESSION);
        session.setUserId("user-1");
        session.setProjectId(PROJECT);
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.of(session));

        ResolvedSkill skill = new ResolvedSkill(
                "decision-frame", "Decision Frame", "desc", "1.0.0",
                List.of(), null, List.of(), List.of(skillFolder),
                List.of(), List.of(), List.of(), true, SkillScope.PROJECT);
        when(skillResolver.resolve(any(SkillScopeContext.class), eq("decision-frame")))
                .thenReturn(Optional.of(skill));

        LookupResult res = mock(LookupResult.class);
        when(res.content()).thenReturn("# Decision protocol\n");
        when(res.source()).thenReturn(LookupResult.Source.PROJECT);
        when(documentService.lookupCascade(TENANT, PROJECT, skillFolder + "PROTOCOL.md"))
                .thenReturn(Optional.of(res));

        Map<String, Object> out = tool.invoke(Map.of("name", "PROTOCOL"), ctx);
        assertThat(out).containsEntry("name", "PROTOCOL")
                .containsEntry("folder", skillFolder);
    }

    @Test
    void recipePathsTakePrecedenceOverSkillPaths() {
        // Both recipe and skill point at the same stem — recipe folder
        // appears first in the effective list, so its hit wins.
        String skillFolder = "manuals/decision/";
        process.setActiveSkills(List.of(
                ActiveSkillRefEmbedded.builder().name("decision-frame").build()));

        SessionDocument session = new SessionDocument();
        session.setSessionId(SESSION);
        session.setUserId("user-1");
        session.setProjectId(PROJECT);
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.of(session));

        ResolvedSkill skill = new ResolvedSkill(
                "decision-frame", "Decision Frame", "desc", "1.0.0",
                List.of(), null, List.of(), List.of(skillFolder),
                List.of(), List.of(), List.of(), true, SkillScope.PROJECT);
        when(skillResolver.resolve(any(SkillScopeContext.class), eq("decision-frame")))
                .thenReturn(Optional.of(skill));

        // Recipe folder (FOLDER from the parent setUp) wins for stem "STYLE".
        Map<String, Object> out = tool.invoke(Map.of("name", "STYLE"), ctx);
        assertThat(out).containsEntry("folder", FOLDER);
    }
}
