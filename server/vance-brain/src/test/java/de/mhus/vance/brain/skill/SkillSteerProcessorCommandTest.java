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
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillSteerProcessorCommandTest {

    @Mock private ThinkProcessService thinkProcessService;
    @Mock private SessionService sessionService;
    @Mock private SkillResolver skillResolver;
    @Mock private SkillCommandRunner skillCommandRunner;
    @Mock private ProcessEventEmitter eventEmitter;
    @Mock private SkillSpawnRunner skillSpawnRunner;

    @Captor private ArgumentCaptor<de.mhus.vance.shared.thinkprocess.PendingMessageDocument> pendingCaptor;

    private SkillSteerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SkillSteerProcessor(
                thinkProcessService, sessionService, skillResolver, skillCommandRunner,
                eventEmitter, new PromptTemplateRenderer(), skillSpawnRunner);
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
        return skill(name, lifecycle, activate, deactivate, action, null, false, List.of());
    }

    private ResolvedSkill skill(
            String name, SkillLifecycle lifecycle,
            List<EngineCommand> activate, List<EngineCommand> deactivate,
            String action, String body,
            boolean consumesArgs, List<ResolvedSkill.Argument> arguments) {
        return new ResolvedSkill(
                name, name, "desc", "1.0.0",
                List.of(), body, List.of(), List.of(), List.of(), List.of(),
                List.of(), true, SkillScope.VANCE, activate, deactivate, lifecycle,
                consumesArgs, arguments, action);
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
    void activate_shotSkillWithBodyAndNoAction_firesBodyAsTurnPrompt() {
        ResolvedSkill macro = skill("review", SkillLifecycle.SHOT, List.of(), List.of(),
                /*action*/ null, "Review the current changes now.", false, List.of());
        when(skillResolver.resolve(any(), eq("review"))).thenReturn(Optional.of(macro));
        ThinkProcessDocument p = process(List.of());

        processor.activate(p, "review", false);

        // A shot skill never registers, so its body can only ever be a
        // turn-prompt — this is the prompt-macro path.
        verify(thinkProcessService, never()).replaceActiveSkills(anyString(), any());
        verify(thinkProcessService).appendPending(eq("p1"), pendingCaptor.capture());
        verify(eventEmitter).scheduleTurn(eq("p1"));
        assertThat(pendingCaptor.getValue().getContent())
                .contains("Review the current changes now.");
    }

    @Test
    void activate_shotSkillWithBodyAndAction_prefersAction() {
        ResolvedSkill both = skill("review", SkillLifecycle.SHOT, List.of(), List.of(),
                "Kick off now.", "Body text.", false, List.of());
        when(skillResolver.resolve(any(), eq("review"))).thenReturn(Optional.of(both));

        processor.activate(process(List.of()), "review", false);

        verify(thinkProcessService).appendPending(eq("p1"), pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().getContent())
                .contains("Kick off now.")
                .doesNotContain("Body text.");
    }

    @Test
    void activate_stickySkillWithBody_doesNotFireBodyAsTurnPrompt() {
        ResolvedSkill sticky = skill("s", SkillLifecycle.STICKY, List.of(), List.of(),
                /*action*/ null, "Sticky body goes into the system prompt.",
                false, List.of());
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(sticky));

        processor.activate(process(List.of()), "s", false);

        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void activate_declaredArgs_renderIntoTurnPrompt() {
        ResolvedSkill macro = skill("review", SkillLifecycle.SHOT, List.of(), List.of(),
                null, "Review {{ args.scope }} now.", true,
                List.of(new ResolvedSkill.Argument("scope", "string", null, false)));
        when(skillResolver.resolve(any(), eq("review"))).thenReturn(Optional.of(macro));

        processor.activate(process(List.of()), "review", false, "src/main/java", "u1");

        verify(thinkProcessService).appendPending(eq("p1"), pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().getContent())
                .isEqualTo("Review src/main/java now.");
    }

    @Test
    void activate_declaredArgs_missingRequired_rejectsActivation() {
        ResolvedSkill macro = skill("review", SkillLifecycle.SHOT, List.of(), List.of(),
                null, "Review {{ args.scope }}.", true,
                List.of(new ResolvedSkill.Argument("scope", "string", null, true)));
        when(skillResolver.resolve(any(), eq("review"))).thenReturn(Optional.of(macro));
        ThinkProcessDocument p = process(List.of());

        assertThatThrownBy(() -> processor.activate(p, "review", false, null, "u1"))
                .isInstanceOf(SkillArgumentException.class)
                .hasMessageContaining("scope");

        verify(skillCommandRunner, never()).run(any(), any(), anyString(), anyString());
        verify(thinkProcessService, never()).appendPending(anyString(), any());
    }

    @Test
    void activate_undeclaredArgs_injectedAsUserMessage() {
        ResolvedSkill plain = skill("s", SkillLifecycle.STICKY, List.of(), List.of(),
                null, "Body.", /*consumesArgs*/ false, List.of());
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(plain));

        processor.activate(process(List.of()), "s", false, "look at PR 42", "u1");

        // The skill declares no arguments:, so the trailing text must
        // still reach the model — as a plain user message.
        verify(thinkProcessService).appendPending(eq("p1"), pendingCaptor.capture());
        verify(eventEmitter).scheduleTurn(eq("p1"));
        assertThat(pendingCaptor.getValue().getContent()).isEqualTo("look at PR 42");
        assertThat(pendingCaptor.getValue().getFromUser()).isEqualTo("u1");
    }

    @Test
    void activate_declaredArgs_notAlsoInjectedAsUserMessage() {
        ResolvedSkill macro = skill("s", SkillLifecycle.STICKY, List.of(), List.of(),
                null, "Body {{ args.text }}.", /*consumesArgs*/ true, List.of());
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(macro));

        processor.activate(process(List.of()), "s", false, "look at PR 42", "u1");

        // Consumed by the template — a second delivery as a chat message
        // would duplicate it.
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void activate_declaredArgs_persistedForLaterTurns() {
        ResolvedSkill macro = skill("s", SkillLifecycle.STICKY, List.of(), List.of(),
                null, "Body {{ args.text }}.", /*consumesArgs*/ true, List.of());
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(macro));

        SkillSteerProcessor.ActivationResult result =
                processor.activate(process(List.of()), "s", false, "scope x", "u1");

        assertThat(result.activeAfter())
                .singleElement()
                .extracting(ActiveSkillRefEmbedded::getArgs)
                .isEqualTo("scope x");
    }

    @Test
    void activate_alreadyActive_updatesArgs() {
        ResolvedSkill macro = skill("s", SkillLifecycle.STICKY, List.of(), List.of(),
                null, "Body {{ args.text }}.", /*consumesArgs*/ true, List.of());
        when(skillResolver.resolve(any(), eq("s"))).thenReturn(Optional.of(macro));
        ActiveSkillRefEmbedded existing = ActiveSkillRefEmbedded.builder()
                .name("s").oneShot(false).fromRecipe(false).args("old").build();
        ThinkProcessDocument p = process(List.of(existing));

        SkillSteerProcessor.ActivationResult result =
                processor.activate(p, "s", false, "new scope", "u1");

        assertThat(result.newlyActivated()).isFalse();
        assertThat(result.activeAfter())
                .singleElement()
                .extracting(ActiveSkillRefEmbedded::getArgs)
                .isEqualTo("new scope");
        verify(thinkProcessService).replaceActiveSkills(eq("p1"), any());
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
