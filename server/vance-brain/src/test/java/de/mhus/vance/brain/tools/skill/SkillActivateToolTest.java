package de.mhus.vance.brain.tools.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.skill.ActivationRoute;
import de.mhus.vance.brain.skill.DisabledSkillException;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillLifecycle;
import de.mhus.vance.brain.skill.SkillRun;
import de.mhus.vance.brain.skill.SkillSteerProcessor;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
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
 * The agent-side activation entry: thin adapter, so the contract here is
 * that it forwards to the /skill funnel verbatim and refuses the states
 * where "activate" would silently mean "do nothing".
 */
class SkillActivateToolTest {

    private ThinkProcessService thinkProcessService;
    private SkillSteerProcessor steerProcessor;
    private SkillActivateTool tool;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        steerProcessor = mock(SkillSteerProcessor.class);
        tool = new SkillActivateTool(thinkProcessService, steerProcessor);
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

    private ResolvedSkill skill(boolean enabled) {
        return new ResolvedSkill(
                "design-blueprint",
                "Design Blueprint",
                "desc",
                "1.0.0",
                List.of(),
                "body",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                enabled,
                de.mhus.vance.api.skills.SkillScope.RESOURCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                null);
    }

    @Test
    void invoke_forwardsToTheSkillFunnelAndReportsActive() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill skill = skill(true);
        ActiveSkillRefEmbedded active = new ActiveSkillRefEmbedded();
        active.setName("design-blueprint");
        when(steerProcessor.activate(
                        any(), eq("design-blueprint"), eq(false), eq(ActivationRoute.AGENT), eq(null), eq("alice")))
                .thenReturn(new SkillSteerProcessor.ActivationResult(skill, true, List.of(active)));

        Map<String, Object> out = tool.invoke(Map.of("name", "design-blueprint"), ctx("p1"));

        verify(steerProcessor)
                .activate(any(), eq("design-blueprint"), eq(false), eq(ActivationRoute.AGENT), eq(null), eq("alice"));
        assertThat(out.get("skill")).isEqualTo("design-blueprint");
        assertThat(out.get("newlyActivated")).isEqualTo(true);
        assertThat(out.get("activeSkills")).isEqualTo(List.of("design-blueprint"));
    }

    @Test
    void invoke_passesArgsAndOnceThrough() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        when(steerProcessor.activate(
                        any(), eq("s"), eq(true), eq(ActivationRoute.AGENT), eq("landing page"), eq("alice")))
                .thenReturn(new SkillSteerProcessor.ActivationResult(skill(true), true, List.of()));

        tool.invoke(Map.of("name", "s", "args", "landing page", "once", true), ctx("p1"));

        verify(steerProcessor)
                .activate(any(), eq("s"), eq(true), eq(ActivationRoute.AGENT), eq("landing page"), eq("alice"));
    }

    @Test
    void invoke_withoutProcessIdRefuses() {
        assertThatThrownBy(() -> tool.invoke(Map.of("name", "s"), ctx(null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("think-process");
        verify(steerProcessor, never())
                .activate(any(), anyString(), anyBoolean(), any(ActivationRoute.class), any(), any());
    }

    @Test
    void invoke_unknownAndDisabledSkillsSurfaceTheirFunnelErrors() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        when(steerProcessor.activate(any(), eq("ghost"), eq(false), eq(ActivationRoute.AGENT), any(), any()))
                .thenThrow(new UnknownSkillException("ghost"));
        when(steerProcessor.activate(any(), eq("template"), eq(false), eq(ActivationRoute.AGENT), any(), any()))
                .thenThrow(new DisabledSkillException("template"));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "ghost"), ctx("p1")))
                .hasMessageContaining("Unknown skill");
        assertThatThrownBy(() -> tool.invoke(Map.of("name", "template"), ctx("p1")))
                .hasMessageContaining("disabled");
    }

    private ResolvedSkill skill(
            String name,
            @org.jspecify.annotations.Nullable String body,
            @org.jspecify.annotations.Nullable String action,
            SkillLifecycle lifecycle,
            SkillRun run) {
        return new ResolvedSkill(
                name,
                name,
                "desc",
                "1.0.0",
                List.of(),
                body,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                de.mhus.vance.api.skills.SkillScope.RESOURCE,
                List.of(),
                List.of(),
                lifecycle,
                false,
                List.of(),
                action,
                run);
    }

    @Test
    void invoke_shotSkillWithPromptReportsOneOffTurnNotActive() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill shot = skill("run-report", "Produce the report now.", null, SkillLifecycle.SHOT, SkillRun.INLINE);
        when(steerProcessor.activate(
                        any(), eq("run-report"), eq(false), eq(ActivationRoute.AGENT), eq(null), eq("alice")))
                .thenReturn(new SkillSteerProcessor.ActivationResult(shot, true, List.of()));

        Map<String, Object> out = tool.invoke(Map.of("name", "run-report"), ctx("p1"));

        assertThat(out.get("activeSkills")).isEqualTo(List.of());
        assertThat((String) out.get("note")).contains("call skill_fire").contains("nothing stays active");
    }

    @Test
    void invoke_shotSkillWithoutPromptReportsCommandsOnly() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill macro = skill("code-guard", null, null, SkillLifecycle.SHOT, SkillRun.INLINE);
        when(steerProcessor.activate(
                        any(), eq("code-guard"), eq(false), eq(ActivationRoute.AGENT), eq(null), eq("alice")))
                .thenReturn(new SkillSteerProcessor.ActivationResult(macro, true, List.of()));

        Map<String, Object> out = tool.invoke(Map.of("name", "code-guard"), ctx("p1"));

        assertThat((String) out.get("note")).contains("activation commands ran").contains("nothing stays active");
    }

    @Test
    void invoke_spawnSkillReportsWorkerAndUnchangedConversation() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill spawn = skill(
                "code-review",
                "Review methodology.",
                "Review the diff.",
                SkillLifecycle.STICKY,
                new SkillRun(SkillRun.Target.SPAWN, "code-review", "none"));
        when(steerProcessor.activate(
                        any(), eq("code-review"), eq(false), eq(ActivationRoute.AGENT), eq(null), eq("alice")))
                .thenReturn(new SkillSteerProcessor.ActivationResult(spawn, true, List.of()));

        Map<String, Object> out = tool.invoke(Map.of("name", "code-review"), ctx("p1"));

        assertThat((String) out.get("note")).contains("worker process has been started");
    }

    @Test
    void invoke_stickyWithActionReportsUnfiredActionAndFireHint() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill sticky = skill(
                "code-review", "Review methodology.", "Review the diff now.", SkillLifecycle.STICKY, SkillRun.INLINE);
        when(steerProcessor.activate(
                        any(), eq("code-review"), eq(false), eq(ActivationRoute.AGENT), eq(null), eq("alice")))
                .thenReturn(new SkillSteerProcessor.ActivationResult(sticky, true, List.of()));

        Map<String, Object> out = tool.invoke(Map.of("name", "code-review"), ctx("p1"));

        assertThat((String) out.get("note"))
                .contains("take effect from your next turn")
                .contains("NOT fired — call skill_fire");
    }

    @Test
    void invoke_onceActivationReportsOneShotClear() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        ResolvedSkill sticky = skill("design-blueprint", "body", null, SkillLifecycle.STICKY, SkillRun.INLINE);
        when(steerProcessor.activate(
                        any(), eq("design-blueprint"), eq(true), eq(ActivationRoute.AGENT), eq(null), eq("alice")))
                .thenReturn(new SkillSteerProcessor.ActivationResult(sticky, true, List.of()));

        Map<String, Object> out = tool.invoke(Map.of("name", "design-blueprint", "once", true), ctx("p1"));

        assertThat((String) out.get("note")).contains("One-shot");
    }
}
