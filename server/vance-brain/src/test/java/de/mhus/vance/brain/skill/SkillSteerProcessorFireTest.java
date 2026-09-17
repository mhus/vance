package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code fire} — the explicit kick-off the agent route needs because
 * {@code skill_activate} never fires an action turn. Pins the four
 * outcomes, the "stored args re-render" rule for sticky fires, and that
 * fire never runs {@code activate:} sequences (those belong to activation).
 */
@ExtendWith(MockitoExtension.class)
class SkillSteerProcessorFireTest {

    @Mock
    private ThinkProcessService thinkProcessService;

    @Mock
    private SessionService sessionService;

    @Mock
    private SkillResolver skillResolver;

    @Mock
    private SkillCommandRunner skillCommandRunner;

    @Mock
    private ProcessEventEmitter eventEmitter;

    @Mock
    private SkillSpawnRunner skillSpawnRunner;

    @Captor
    private ArgumentCaptor<PendingMessageDocument> pendingCaptor;

    private SkillSteerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SkillSteerProcessor(
                thinkProcessService,
                sessionService,
                skillResolver,
                skillCommandRunner,
                eventEmitter,
                new PromptTemplateRenderer(),
                skillSpawnRunner);
    }

    private ThinkProcessDocument process(List<ActiveSkillRefEmbedded> active) {
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .activeSkills(active)
                .build();
    }

    private ResolvedSkill stickySkill(String name, String action, boolean consumesArgs) {
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
                SkillScope.VANCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                consumesArgs,
                List.of(),
                action,
                SkillRun.INLINE);
    }

    private ResolvedSkill shotSkill(String name, String body) {
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
                SkillScope.VANCE,
                List.of(),
                List.of(),
                SkillLifecycle.SHOT,
                false,
                List.of(),
                null,
                SkillRun.INLINE);
    }

    private ResolvedSkill spawnSkill(String name) {
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
                SkillScope.VANCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                "Review the diff.",
                new SkillRun(SkillRun.Target.SPAWN, "code-review", "none"));
    }

    private ActiveSkillRefEmbedded activeRef(String name, String args) {
        return ActiveSkillRefEmbedded.builder()
                .name(name)
                .oneShot(false)
                .fromRecipe(false)
                .args(args)
                .build();
    }

    @Test
    void fire_shotSkill_firesBodyWithoutRegisteringOrRunningActivateCommands() {
        ResolvedSkill shot = shotSkill("run-report", "Produce the report now.");
        when(skillResolver.resolve(any(), eq("run-report"))).thenReturn(Optional.of(shot));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());

        SkillSteerProcessor.FireResult result = processor.fire(process(List.of()), "run-report", null, null);

        assertThat(result.outcome()).isEqualTo(SkillSteerProcessor.FireResult.Outcome.FIRED);
        verify(thinkProcessService).appendPending(eq("p1"), pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().getContent()).isEqualTo("Produce the report now.");
        verify(eventEmitter).scheduleTurn("p1");
        verify(skillCommandRunner, never()).run(any(), any(), anyString(), anyString());
        verify(thinkProcessService, never()).replaceActiveSkills(anyString(), any());
    }

    @Test
    void fire_shotSkillWithoutPrompt_reportsNothingToFire() {
        ResolvedSkill macro = shotSkill("code-guard", null);
        when(skillResolver.resolve(any(), eq("code-guard"))).thenReturn(Optional.of(macro));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());

        SkillSteerProcessor.FireResult result = processor.fire(process(List.of()), "code-guard", null, null);

        assertThat(result.outcome()).isEqualTo(SkillSteerProcessor.FireResult.Outcome.NO_TURN_PROMPT);
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void fire_stickySkillNotActive_refusesInsteadOfActivating() {
        ResolvedSkill sticky = stickySkill("code-review", "Review the diff.", false);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(sticky));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());

        SkillSteerProcessor.FireResult result = processor.fire(process(List.of()), "code-review", null, null);

        assertThat(result.outcome()).isEqualTo(SkillSteerProcessor.FireResult.Outcome.NOT_ACTIVE);
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
        // A fire is not a hidden activation — nothing is registered.
        verify(thinkProcessService, never()).replaceActiveSkills(anyString(), any());
    }

    @Test
    void fire_stickyActiveSkill_rendersWithTheArgsItWasActivatedWith() {
        ResolvedSkill sticky = stickySkill("code-review", "Review {{ args.text }} now.", true);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(sticky));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        ThinkProcessDocument p = process(List.of(activeRef("code-review", "src/main/java")));

        // No args of its own — the stored activation args are part of the
        // skill's configuration and re-render here.
        SkillSteerProcessor.FireResult result = processor.fire(p, "code-review", null, null);

        assertThat(result.outcome()).isEqualTo(SkillSteerProcessor.FireResult.Outcome.FIRED);
        verify(thinkProcessService).appendPending(eq("p1"), pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().getContent()).isEqualTo("Review src/main/java now.");
    }

    @Test
    void fire_stickyActiveSkill_ownArgsOverrideWithoutReconfiguring() {
        ResolvedSkill sticky = stickySkill("code-review", "Review {{ args.text }} now.", true);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(sticky));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        ThinkProcessDocument p = process(List.of(activeRef("code-review", "src/main/java")));

        SkillSteerProcessor.FireResult result = processor.fire(p, "code-review", "tests only", null);

        assertThat(result.outcome()).isEqualTo(SkillSteerProcessor.FireResult.Outcome.FIRED);
        verify(thinkProcessService).appendPending(eq("p1"), pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().getContent()).isEqualTo("Review tests only now.");
        // A fire renders, it does not reconfigure — the stored args on the
        // ref stay what activation put there.
        verify(thinkProcessService, never()).replaceActiveSkills(anyString(), any());
    }

    @Test
    void fire_stickyActiveSkillWithoutAction_reportsNothingToFire() {
        ResolvedSkill sticky = stickySkill("design-blueprint", null, false);
        when(skillResolver.resolve(any(), eq("design-blueprint"))).thenReturn(Optional.of(sticky));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        ThinkProcessDocument p = process(List.of(activeRef("design-blueprint", null)));

        SkillSteerProcessor.FireResult result = processor.fire(p, "design-blueprint", null, null);

        assertThat(result.outcome()).isEqualTo(SkillSteerProcessor.FireResult.Outcome.NO_TURN_PROMPT);
        verify(thinkProcessService, never()).appendPending(anyString(), any());
    }

    @Test
    void fire_spawnSkill_reportsSpawnOutcomeAndSchedulesNothing() {
        ResolvedSkill spawn = spawnSkill("code-review");
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(spawn));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());

        SkillSteerProcessor.FireResult result = processor.fire(process(List.of()), "code-review", null, null);

        assertThat(result.outcome()).isEqualTo(SkillSteerProcessor.FireResult.Outcome.SPAWN_SKILL);
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
        verify(skillSpawnRunner, never()).spawn(any(), any(), any());
    }

    @Test
    void fire_unknownAndRecipeLockedSkillsSurfaceTheirFunnelErrors() {
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        when(skillResolver.resolve(any(), eq("ghost"))).thenReturn(Optional.empty());
        ThinkProcessDocument locked = ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .sessionId("s1")
                .allowedSkillsOverride(java.util.Set.of("review-mode"))
                .build();

        assertThatThrownBy(() -> processor.fire(process(List.of()), "ghost", null, null))
                .isInstanceOf(UnknownSkillException.class);
        assertThatThrownBy(() -> processor.fire(locked, "code-review", null, null))
                .isInstanceOf(SkillNotAllowedByRecipeException.class);
    }
}
