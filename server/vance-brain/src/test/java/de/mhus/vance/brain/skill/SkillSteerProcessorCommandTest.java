package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillSteerProcessorCommandTest {

    @Mock private ThinkProcessService thinkProcessService;
    @Mock private SessionService sessionService;
    @Mock private SkillResolver skillResolver;
    @Mock private SkillCommandRunner skillCommandRunner;
    @Mock private ProcessEventEmitter eventEmitter;

    private SkillSteerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SkillSteerProcessor(
                thinkProcessService, sessionService, skillResolver, skillCommandRunner,
                eventEmitter);
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
    }

    private ThinkProcessDocument process(List<ActiveSkillRefEmbedded> active) {
        return ThinkProcessDocument.builder()
                .id("p1").tenantId("acme").sessionId("s1")
                .activeSkills(active)
                .build();
    }

    private ResolvedSkill skill(
            String name, SkillLifecycle lifecycle,
            List<EngineCommand> activate, List<EngineCommand> deactivate) {
        return skill(name, lifecycle, activate, deactivate, null);
    }

    private ResolvedSkill skill(
            String name, SkillLifecycle lifecycle,
            List<EngineCommand> activate, List<EngineCommand> deactivate,
            String action) {
        return new ResolvedSkill(
                name, name, "desc", "1.0.0",
                List.of(), null, List.of(), List.of(), List.of(), List.of(),
                List.of(), true, SkillScope.VANCE, activate, deactivate, lifecycle, action);
    }

    @Test
    void activate_shotSkill_firesActivateAndDoesNotPersist() {
        List<EngineCommand> activate = List.of(EngineCommand.parse("echo hi"));
        ResolvedSkill shot = skill("cfg", SkillLifecycle.SHOT, activate, List.of());
        when(skillResolver.resolve(any(), eq("cfg"))).thenReturn(Optional.of(shot));
        ThinkProcessDocument p = process(List.of());

        SkillSteerProcessor.ActivationResult result = processor.activate(p, "cfg", false);

        verify(skillCommandRunner).run(eq(p), eq(activate), eq("activate"), eq("cfg"));
        verify(thinkProcessService, never()).replaceActiveSkills(anyString(), any());
        assertThat(result.activeAfter()).isEmpty();
    }

    @Test
    void activate_stickySkill_persistsAndFiresActivate() {
        List<EngineCommand> activate = List.of(EngineCommand.parse("echo go"));
        ResolvedSkill sticky = skill("s", SkillLifecycle.STICKY, activate, List.of());
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(sticky));
        ThinkProcessDocument p = process(List.of());

        SkillSteerProcessor.ActivationResult result = processor.activate(p, "s", false);

        verify(thinkProcessService).replaceActiveSkills(eq("p1"), any());
        verify(skillCommandRunner).run(eq(p), eq(activate), eq("activate"), eq("s"));
        assertThat(result.newlyActivated()).isTrue();
    }

    @Test
    void activate_stickySkillWithAction_schedulesTurnAfterActivate() {
        List<EngineCommand> activate = List.of(EngineCommand.parse("echo go"));
        ResolvedSkill withAction = skill("s", SkillLifecycle.STICKY, activate, List.of(),
                "Review the current diff.");
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(withAction));
        ThinkProcessDocument p = process(List.of());

        processor.activate(p, "s", true);

        verify(skillCommandRunner).run(eq(p), eq(activate), eq("activate"), eq("s"));
        verify(thinkProcessService).appendPending(eq("p1"), any());
        verify(eventEmitter).scheduleTurn(eq("p1"));
    }

    @Test
    void activate_shotSkillWithAction_firesActionTurn() {
        ResolvedSkill shot = skill("cfg", SkillLifecycle.SHOT, List.of(), List.of(),
                "Kick off the analysis.");
        when(skillResolver.resolve(any(), eq("cfg"))).thenReturn(Optional.of(shot));
        ThinkProcessDocument p = process(List.of());

        processor.activate(p, "cfg", false);

        verify(thinkProcessService).appendPending(eq("p1"), any());
        verify(eventEmitter).scheduleTurn(eq("p1"));
    }

    @Test
    void activate_withoutAction_schedulesNoTurn() {
        ResolvedSkill plain = skill("s", SkillLifecycle.STICKY, List.of(), List.of());
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(plain));
        ThinkProcessDocument p = process(List.of());

        processor.activate(p, "s", false);

        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void activate_withRunActionFalse_suppressesActionTurn() {
        List<EngineCommand> activate = List.of(EngineCommand.parse("echo go"));
        ResolvedSkill withAction = skill("s", SkillLifecycle.STICKY, activate, List.of(),
                "Review the current diff.");
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(withAction));
        ThinkProcessDocument p = process(List.of());

        // auto-trigger path — activation during an in-flight turn
        processor.activate(p, "s", true, /*runAction*/ false);

        verify(skillCommandRunner).run(eq(p), eq(activate), eq("activate"), eq("s"));
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void activate_alreadyActiveSkillWithAction_doesNotRefireAction() {
        ResolvedSkill withAction = skill("s", SkillLifecycle.STICKY, List.of(), List.of(),
                "Review the current diff.");
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(withAction));
        ActiveSkillRefEmbedded existing = ActiveSkillRefEmbedded.builder()
                .name("s").oneShot(false).fromRecipe(false).build();
        ThinkProcessDocument p = process(List.of(existing));

        SkillSteerProcessor.ActivationResult result = processor.activate(p, "s", false);

        assertThat(result.newlyActivated()).isFalse();
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void clear_firesDeactivateOfRemovedSkill() {
        List<EngineCommand> deactivate = List.of(EngineCommand.parse("echo bye"));
        ResolvedSkill sticky = skill("s", SkillLifecycle.STICKY, List.of(), deactivate);
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(sticky));
        ActiveSkillRefEmbedded ref = ActiveSkillRefEmbedded.builder()
                .name("s").fromRecipe(false).build();
        ThinkProcessDocument p = process(List.of(ref));

        processor.clear(p, "s");

        verify(skillCommandRunner).run(eq(p), eq(deactivate), eq("deactivate"), eq("s"));
    }
}
