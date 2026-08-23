package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code run.target: spawn} — the skill acts in a fresh worker instead of
 * in the calling process. See {@code planning/skill-spawn-target.md}.
 */
@ExtendWith(MockitoExtension.class)
class SkillSteerProcessorSpawnTest {

    @Mock private ThinkProcessService thinkProcessService;
    @Mock private SessionService sessionService;
    @Mock private SkillResolver skillResolver;
    @Mock private SkillCommandRunner skillCommandRunner;
    @Mock private ProcessEventEmitter eventEmitter;
    @Mock private SkillSpawnRunner skillSpawnRunner;

    @Captor private ArgumentCaptor<List<ActiveSkillRefEmbedded>> skillsCaptor;
    @Captor private ArgumentCaptor<PendingMessageDocument> pendingCaptor;

    private SkillSteerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SkillSteerProcessor(
                thinkProcessService, sessionService, skillResolver, skillCommandRunner,
                eventEmitter, new PromptTemplateRenderer(), skillSpawnRunner);
    }

    private ThinkProcessDocument process(String id, List<ActiveSkillRefEmbedded> active) {
        return ThinkProcessDocument.builder()
                .id(id).tenantId("acme").projectId("proj").sessionId("s1")
                .activeSkills(active)
                .build();
    }

    private ResolvedSkill spawnSkill(String name, String action, boolean consumesArgs) {
        return new ResolvedSkill(
                name, name, "desc", "1.0.0",
                List.of(), "Review methodology.", List.of(), List.of(), List.of(), List.of(),
                List.of(), true, SkillScope.VANCE, List.of(), List.of(),
                SkillLifecycle.STICKY, consumesArgs, List.of(), action,
                new SkillRun(SkillRun.Target.SPAWN, "code-review", "none"));
    }

    /** Makes the runner mock invoke its activation callback with {@code child}. */
    private void spawnYields(ThinkProcessDocument child) {
        when(skillSpawnRunner.spawn(any(), any(), any())).thenAnswer(inv -> {
            Consumer<ThinkProcessDocument> activation = inv.getArgument(2);
            activation.accept(child);
            return child.getId();
        });
    }

    @Test
    void activate_spawnSkill_leavesCallerUntouched() {
        ResolvedSkill skill = spawnSkill("code-review", "Review the diff.", false);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        spawnYields(process("c1", List.of()));
        ThinkProcessDocument parent = process("p1", List.of());

        SkillSteerProcessor.ActivationResult result =
                processor.activate(parent, "code-review", false);

        assertThat(result.activeAfter()).isEmpty();
        verify(thinkProcessService, never()).replaceActiveSkills(eq("p1"), any());
        verify(thinkProcessService, never()).appendPending(eq("p1"), any());
        verify(eventEmitter, never()).scheduleTurn(eq("p1"));
    }

    @Test
    void activate_spawnSkill_childCarriesSkillStickyAndGetsTheTurn() {
        ResolvedSkill skill = spawnSkill("code-review", "Review the diff.", false);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        spawnYields(process("c1", List.of()));

        processor.activate(process("p1", List.of()), "code-review", false);

        verify(thinkProcessService).replaceActiveSkills(eq("c1"), skillsCaptor.capture());
        assertThat(skillsCaptor.getValue())
                .singleElement()
                .extracting(ActiveSkillRefEmbedded::getName)
                .isEqualTo("code-review");
        verify(thinkProcessService).appendPending(eq("c1"), pendingCaptor.capture());
        verify(eventEmitter).scheduleTurn(eq("c1"));
        assertThat(pendingCaptor.getValue().getContent()).isEqualTo("Review the diff.");
    }

    @Test
    void activate_spawnSkill_argsReachTheChildSoTheStickyBodyCanRenderThem() {
        ResolvedSkill skill = spawnSkill(
                "code-review", "Review {{ args.text }} now.", /*consumesArgs*/ true);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        spawnYields(process("c1", List.of()));

        processor.activate(process("p1", List.of()), "code-review", false,
                "src/main/java", "u1");

        // On the ref, so every later turn re-binds them for the body …
        verify(thinkProcessService).replaceActiveSkills(eq("c1"), skillsCaptor.capture());
        assertThat(skillsCaptor.getValue())
                .singleElement()
                .extracting(ActiveSkillRefEmbedded::getArgs)
                .isEqualTo("src/main/java");
        // … and rendered into the kick-off turn.
        verify(thinkProcessService).appendPending(eq("c1"), pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().getContent())
                .isEqualTo("Review src/main/java now.");
    }

    @Test
    void activate_spawnSkill_carriesOnceThroughToTheChild() {
        // run decides the place, --once the duration; SkillRun calls the two
        // orthogonal. Dropping the flag at the spawn made the child register
        // the skill sticky although the caller asked for a single use.
        ResolvedSkill skill = spawnSkill("code-review", "Review the diff.", false);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        spawnYields(process("c1", List.of()));

        processor.activate(process("p1", List.of()), "code-review", /*oneShot*/ true);

        verify(thinkProcessService).replaceActiveSkills(eq("c1"), skillsCaptor.capture());
        assertThat(skillsCaptor.getValue())
                .singleElement()
                .extracting(ActiveSkillRefEmbedded::isOneShot)
                .isEqualTo(true);
    }

    @Test
    void activate_spawnSkill_childDoesNotSpawnAgain() {
        ResolvedSkill skill = spawnSkill("code-review", "Review the diff.", false);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        spawnYields(process("c1", List.of()));

        processor.activate(process("p1", List.of()), "code-review", false);

        // The child-side activation must not re-enter the spawn branch —
        // otherwise every worker would spawn a worker.
        verify(skillSpawnRunner, times(1)).spawn(any(), any(), any());
    }

    @Test
    void activate_spawnSkill_onAutoTriggerPath_doesNotSpawn() {
        ResolvedSkill skill = spawnSkill("code-review", "Review the diff.", false);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        ThinkProcessDocument parent = process("p1", List.of());

        // runAction=false is the auto-trigger path: a turn is already in
        // flight, and starting a worker from a keyword match would be both
        // expensive and surprising.
        SkillSteerProcessor.ActivationResult result =
                processor.activate(parent, "code-review", false, /*runAction*/ false);

        verify(skillSpawnRunner, never()).spawn(any(), any(), any());
        assertThat(result.newlyActivated()).isFalse();
        verify(thinkProcessService, never()).replaceActiveSkills(anyString(), any());
    }

    @Test
    void activate_spawnSkillAlreadyActiveOnThisProcess_activatesInlineInstead() {
        ResolvedSkill skill = spawnSkill("code-review", "Review the diff.", false);
        when(skillResolver.resolve(any(), eq("code-review"))).thenReturn(Optional.of(skill));
        when(sessionService.findBySessionId(anyString())).thenReturn(Optional.empty());
        ActiveSkillRefEmbedded existing = ActiveSkillRefEmbedded.builder()
                .name("code-review").oneShot(false).fromRecipe(false).build();

        // This is the worker itself being re-invoked: it already carries
        // the skill, so there is nothing to spawn.
        SkillSteerProcessor.ActivationResult result =
                processor.activate(process("c1", List.of(existing)), "code-review", false);

        verify(skillSpawnRunner, never()).spawn(any(), any(), any());
        assertThat(result.newlyActivated()).isFalse();
    }
}
