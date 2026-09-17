package de.mhus.vance.brain.tools.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillLifecycle;
import de.mhus.vance.brain.skill.SkillRun;
import de.mhus.vance.brain.skill.SkillSteerProcessor;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent-side fire entry: thin adapter, so the contract here is that it
 * forwards to the funnel verbatim, refuses without a think-process, and
 * maps every outcome to the "what to do instead" sentence instead of a
 * bare boolean.
 */
class SkillFireToolTest {

    private ThinkProcessService thinkProcessService;
    private SkillSteerProcessor steerProcessor;
    private SkillFireTool tool;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        steerProcessor = mock(SkillSteerProcessor.class);
        tool = new SkillFireTool(thinkProcessService, steerProcessor);
    }

    private ToolInvocationContext ctx(String processId) {
        return new ToolInvocationContext("acme", "web", null, processId, "alice");
    }

    private ThinkProcessDocument process() {
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .sessionId("s1")
                .build();
    }

    private ResolvedSkill sticky(String name, String action) {
        return new ResolvedSkill(
                name,
                name,
                "desc",
                "1.0.0",
                List.of(),
                "Methodology.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                de.mhus.vance.api.skills.SkillScope.RESOURCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                action,
                SkillRun.INLINE);
    }

    @Test
    void invoke_forwardsToFireAndReportsFired() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill skill = sticky("code-review", "Review the diff.");
        when(steerProcessor.fire(any(), eq("code-review"), eq(null), eq("alice")))
                .thenReturn(new SkillSteerProcessor.FireResult(skill, SkillSteerProcessor.FireResult.Outcome.FIRED));

        Map<String, Object> out = tool.invoke(Map.of("name", "code-review"), ctx("p1"));

        verify(steerProcessor).fire(any(), eq("code-review"), eq(null), eq("alice"));
        assertThat(out.get("skill")).isEqualTo("code-review");
        assertThat(out.get("fired")).isEqualTo(true);
        assertThat(out.get("outcome")).isEqualTo("fired");
        assertThat((String) out.get("note")).contains("Turn scheduled");
    }

    @Test
    void invoke_passesArgsThrough() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill skill = sticky("code-review", "Review {{ args.text }}.");
        when(steerProcessor.fire(any(), eq("code-review"), eq("src/main/java"), eq("alice")))
                .thenReturn(new SkillSteerProcessor.FireResult(skill, SkillSteerProcessor.FireResult.Outcome.FIRED));

        tool.invoke(Map.of("name", "code-review", "args", "src/main/java"), ctx("p1"));

        verify(steerProcessor).fire(any(), eq("code-review"), eq("src/main/java"), eq("alice"));
    }

    @Test
    void invoke_withoutProcessIdRefuses() {
        assertThatThrownBy(() -> tool.invoke(Map.of("name", "s"), ctx(null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("think-process");
        verify(steerProcessor, never()).fire(any(), anyString(), any(), any());
    }

    @Test
    void invoke_notActiveOutcomeNamesTheActivateFirstPath() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill skill = sticky("code-review", "Review the diff.");
        when(steerProcessor.fire(any(), eq("code-review"), eq(null), eq("alice")))
                .thenReturn(
                        new SkillSteerProcessor.FireResult(skill, SkillSteerProcessor.FireResult.Outcome.NOT_ACTIVE));

        Map<String, Object> out = tool.invoke(Map.of("name", "code-review"), ctx("p1"));

        assertThat(out.get("fired")).isEqualTo(false);
        assertThat((String) out.get("note")).contains("skill_activate");
    }

    @Test
    void invoke_spawnOutcomeNamesTheWorkerPath() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill skill = new ResolvedSkill(
                "code-review",
                "Code Review",
                "desc",
                "1.0.0",
                List.of(),
                "Methodology.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                de.mhus.vance.api.skills.SkillScope.RESOURCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                "Review the diff.",
                new SkillRun(SkillRun.Target.SPAWN, "code-review", "none"));
        when(steerProcessor.fire(any(), eq("code-review"), eq(null), eq("alice")))
                .thenReturn(
                        new SkillSteerProcessor.FireResult(skill, SkillSteerProcessor.FireResult.Outcome.SPAWN_SKILL));

        Map<String, Object> out = tool.invoke(Map.of("name", "code-review"), ctx("p1"));

        assertThat(out.get("fired")).isEqualTo(false);
        assertThat((String) out.get("note")).contains("fresh worker");
    }

    @Test
    void invoke_noTurnPromptOutcomeSaysThereIsNothingToFire() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill skill = sticky("design-blueprint", null);
        when(steerProcessor.fire(any(), eq("design-blueprint"), eq(null), eq("alice")))
                .thenReturn(new SkillSteerProcessor.FireResult(
                        skill, SkillSteerProcessor.FireResult.Outcome.NO_TURN_PROMPT));

        Map<String, Object> out = tool.invoke(Map.of("name", "design-blueprint"), ctx("p1"));

        assertThat((String) out.get("note")).contains("nothing to fire");
    }

    @Test
    void invoke_unknownSkillSurfacesItsFunnelError() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        when(steerProcessor.fire(any(), eq("ghost"), eq(null), eq("alice")))
                .thenThrow(new UnknownSkillException("ghost"));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "ghost"), ctx("p1")))
                .hasMessageContaining("Unknown skill");
    }
}
