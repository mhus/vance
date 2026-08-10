package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillSpawnRunnerTest {

    @Mock private ActionExecutorRegistry actionRegistry;
    @Mock private LaneScheduler laneScheduler;
    @Mock private ThinkProcessService thinkProcessService;

    @Captor private ArgumentCaptor<TriggerAction> actionCaptor;

    private SkillSpawnRunner runner;
    private final List<String> activated = new ArrayList<>();

    @BeforeEach
    void setUp() {
        runner = new SkillSpawnRunner(actionRegistry, laneScheduler, thinkProcessService);
        activated.clear();
    }

    private ThinkProcessDocument parent() {
        return ThinkProcessDocument.builder()
                .id("p1").tenantId("acme").projectId("proj").sessionId("s1")
                .name("chat")
                .build();
    }

    private ResolvedSkill skill() {
        return new ResolvedSkill(
                "code-review", "Code Review", "Review the changes", "1.0.0",
                List.of(), "Body.", List.of(), List.of(), List.of(), List.of(),
                List.of(), true, SkillScope.VANCE, List.of(), List.of(),
                SkillLifecycle.STICKY, false, List.of(), "Review now.",
                new SkillRun(SkillRun.Target.SPAWN, "code-review", "none"));
    }

    private void runLaneTasksInline() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return CompletableFuture.completedFuture(null);
        }).when(laneScheduler).submit(anyString(), any(Runnable.class));
    }

    private static ActionResult alreadyExists(String name) {
        return ActionResult.success(Map.of("status", "already_exists", "name", name));
    }

    @Test
    void spawn_buildsRecipeActionWithoutInitialMessage() {
        when(thinkProcessService.findBySession(eq("acme"), eq("s1"))).thenReturn(List.of());
        when(actionRegistry.execute(any(), any(), any()))
                .thenReturn(ActionResult.scheduled("c1", Map.of("processId", "c1")));
        runLaneTasksInline();
        when(thinkProcessService.findById(eq("c1")))
                .thenReturn(Optional.of(ThinkProcessDocument.builder().id("c1").build()));

        String childId = runner.spawn(parent(), skill(), child -> activated.add(child.getId()));

        assertThat(childId).isEqualTo("c1");
        verify(actionRegistry).execute(
                actionCaptor.capture(), any(TriggerContext.class), eq(TriggerKind.TOOL));
        TriggerAction.Recipe action = (TriggerAction.Recipe) actionCaptor.getValue();
        assertThat(action.recipe()).isEqualTo("code-review");
        assertThat(action.processName()).isEqualTo("code-review-1");
        assertThat(action.inheritContextLevel()).isEqualTo("none");
        // The task rides in with the activation, not with the spawn — an
        // initialMessage would be pushed before the skill is on the child
        // and would be wrapped with the parent's history.
        assertThat(action.initialMessage()).isNull();
        assertThat(activated).containsExactly("c1");
    }

    @Test
    void spawn_picksNextFreeIndexAfterExistingWorkers() {
        when(thinkProcessService.findBySession(eq("acme"), eq("s1"))).thenReturn(List.of(
                ThinkProcessDocument.builder().id("a").name("code-review-1").build(),
                ThinkProcessDocument.builder().id("b").name("code-review-7").build(),
                ThinkProcessDocument.builder().id("c").name("code-review-of-tuesday").build(),
                ThinkProcessDocument.builder().id("d").name("chat").build()));
        when(actionRegistry.execute(any(), any(), any()))
                .thenReturn(ActionResult.scheduled("c1", Map.of("processId", "c1")));
        runLaneTasksInline();
        when(thinkProcessService.findById(eq("c1")))
                .thenReturn(Optional.of(ThinkProcessDocument.builder().id("c1").build()));

        runner.spawn(parent(), skill(), child -> activated.add(child.getId()));

        verify(actionRegistry).execute(
                actionCaptor.capture(), any(TriggerContext.class), any());
        assertThat(((TriggerAction.Recipe) actionCaptor.getValue()).processName())
                .isEqualTo("code-review-8");
    }

    @Test
    void spawn_onNameCollision_retriesWithNextIndex() {
        when(thinkProcessService.findBySession(eq("acme"), eq("s1"))).thenReturn(List.of());
        // A concurrent spawn in the same session took the name between our
        // scan and our create — the executor answers with an idempotent
        // soft-success that carries no processId.
        when(actionRegistry.execute(any(), any(), any()))
                .thenReturn(alreadyExists("code-review-1"))
                .thenReturn(ActionResult.scheduled("c2", Map.of("processId", "c2")));
        runLaneTasksInline();
        when(thinkProcessService.findById(eq("c2")))
                .thenReturn(Optional.of(ThinkProcessDocument.builder().id("c2").build()));

        String childId = runner.spawn(parent(), skill(), child -> activated.add(child.getId()));

        assertThat(childId).isEqualTo("c2");
        verify(actionRegistry, times(2)).execute(
                actionCaptor.capture(), any(TriggerContext.class), any());
        assertThat(actionCaptor.getAllValues())
                .extracting(a -> ((TriggerAction.Recipe) a).processName())
                .containsExactly("code-review-1", "code-review-2");
    }

    @Test
    void spawn_whenEveryNameIsTaken_fails() {
        when(thinkProcessService.findBySession(eq("acme"), eq("s1"))).thenReturn(List.of());
        when(actionRegistry.execute(any(), any(), any()))
                .thenReturn(alreadyExists("code-review-1"));
        ThinkProcessDocument p = parent();
        ResolvedSkill s = skill();

        assertThatThrownBy(() -> runner.spawn(p, s, child -> activated.add(child.getId())))
                .isInstanceOf(SkillSpawnException.class)
                .hasMessageContaining("no free process name");

        verify(actionRegistry, times(SkillSpawnRunner.MAX_NAME_ATTEMPTS))
                .execute(any(), any(), any());
        assertThat(activated).isEmpty();
    }

    @Test
    void spawn_executorFailure_failsLoudly() {
        when(thinkProcessService.findBySession(eq("acme"), eq("s1"))).thenReturn(List.of());
        when(actionRegistry.execute(any(), any(), any())).thenReturn(
                ActionResult.failure(ActionOutcome.TECHNICAL_ERROR, "unknown recipe", null));
        ThinkProcessDocument p = parent();
        ResolvedSkill s = skill();

        assertThatThrownBy(() -> runner.spawn(p, s, child -> activated.add(child.getId())))
                .isInstanceOf(SkillSpawnException.class)
                .hasMessageContaining("unknown recipe");

        verify(laneScheduler, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void spawn_whenActivationFails_closesTheOrphanedWorker() {
        when(thinkProcessService.findBySession(eq("acme"), eq("s1"))).thenReturn(List.of());
        when(actionRegistry.execute(any(), any(), any()))
                .thenReturn(ActionResult.scheduled("c1", Map.of("processId", "c1")));
        runLaneTasksInline();
        when(thinkProcessService.findById(eq("c1")))
                .thenReturn(Optional.of(ThinkProcessDocument.builder().id("c1").build()));

        runner.spawn(parent(), skill(), child -> {
            throw new IllegalStateException("boom");
        });

        // A worker without its skill has no task and would idle forever.
        verify(thinkProcessService).closeProcess(
                eq("c1"), eq(de.mhus.vance.api.thinkprocess.CloseReason.ABANDONED));
    }
}
