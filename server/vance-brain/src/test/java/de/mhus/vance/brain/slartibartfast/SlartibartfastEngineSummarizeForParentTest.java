package de.mhus.vance.brain.slartibartfast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.slartibartfast.phases.BindingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ClassifyingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ConfirmingPhase;
import de.mhus.vance.brain.slartibartfast.phases.DecomposingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ExecutionValidatingPhase;
import de.mhus.vance.brain.slartibartfast.phases.FramingPhase;
import de.mhus.vance.brain.slartibartfast.phases.GatheringPhase;
import de.mhus.vance.brain.slartibartfast.phases.PersistingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ProposingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ValidatingPhase;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link SlartibartfastEngine#summarizeForParent} behaviour.
 *
 * <p>Regression guard for the live 2026-05-17 incident: without an
 * override, the parent (Arthur) received only
 * "Child process X status=done", saw no produced content, hallucinated
 * "delegated worker finished without providing anything", and Eddie's
 * downstream classifier propagated that as
 * "Essay generation failed with an internal error" — surfacing a
 * spurious ASK_USER to the user moments after the pipeline had
 * actually succeeded.
 *
 * <p>The override flips the failure mode: by lifting the recipe path
 * and the kit-OUTPUT.md path criteria out of {@link ArchitectState}
 * into a structured summary, Arthur's LLM sees the actual deliverables
 * and answers correctly.
 */
class SlartibartfastEngineSummarizeForParentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SlartibartfastEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SlartibartfastEngine(
                mock(ThinkProcessService.class),
                mock(ProcessEventEmitter.class),
                mock(de.mhus.vance.brain.progress.ProgressEmitter.class),
                mock(LaneScheduler.class),
                objectMapper,
                mock(InboxItemService.class),
                mock(RecipeResolver.class),
                /*thinkEngineServiceProvider*/ null,
                mock(FramingPhase.class),
                mock(ConfirmingPhase.class),
                mock(GatheringPhase.class),
                mock(ClassifyingPhase.class),
                mock(DecomposingPhase.class),
                mock(BindingPhase.class),
                mock(ProposingPhase.class),
                mock(ValidatingPhase.class),
                mock(PersistingPhase.class),
                mock(ExecutionValidatingPhase.class),
                mock(de.mhus.vance.brain.slartibartfast.phases.LoadingExistingPhase.class),
                mock(de.mhus.vance.brain.slartibartfast.phases.ExecutionPlanningPhase.class),
                new PathCriteriaLifter());
    }

    @Test
    void done_includesRecipePathAndAllLiftedOutputPaths() {
        ThinkProcessDocument process = process(state(s -> {
            s.setProposedRecipe(RecipeDraft.builder()
                    .name("school-essay-ki-pro-contra").build());
            s.setPersistedRecipePath(
                    "recipes/_slart/4f31b631/school-essay-ki-pro-contra.yaml");
            s.setChildExecutionOutcome("DONE");
            s.setStatus(ArchitectStatus.DONE);
            s.setAcceptanceCriteria(new ArrayList<>(List.of(
                    pathCriterion("essay/research-question.md"),
                    pathCriterion("essay/sources.md"),
                    pathCriterion("essay/outline.md"),
                    pathCriterion("essay/argument-map.md"),
                    pathCriterion("essay/final-essay.md"))));
        }));

        ParentReport report = engine.summarizeForParent(
                process, ProcessEventType.DONE);

        assertThat(report.humanSummary())
                .contains("Slartibartfast finished")
                .contains("school-essay-ki-pro-contra")
                .contains("recipes/_slart/4f31b631/school-essay-ki-pro-contra.yaml")
                .contains("essay/research-question.md")
                .contains("essay/sources.md")
                .contains("essay/outline.md")
                .contains("essay/argument-map.md")
                .contains("essay/final-essay.md")
                .contains("ran it to completion");
        assertThat(report.payload())
                .containsEntry("eventType", "DONE")
                .containsEntry("schemaType", "VOGON_STRATEGY")
                .containsEntry("recipePath",
                        "recipes/_slart/4f31b631/school-essay-ki-pro-contra.yaml")
                .containsEntry("childOutcome", "DONE");
        @SuppressWarnings("unchecked")
        List<String> paths = (List<String>) report.payload().get("outputPaths");
        assertThat(paths).containsExactly(
                "essay/research-question.md", "essay/sources.md",
                "essay/outline.md", "essay/argument-map.md",
                "essay/final-essay.md");
    }

    @Test
    void done_planOnly_saysSoExplicitly() {
        // planOnly runs: Slart builds and persists the recipe but
        // doesn't auto-execute. Parent must hear that no files were
        // produced — only the recipe.
        ThinkProcessDocument process = process(state(s -> {
            s.setProposedRecipe(RecipeDraft.builder()
                    .name("essay-plan").build());
            s.setPersistedRecipePath("recipes/_slart/abc/essay-plan.yaml");
            s.setPlanOnly(true);
            s.setStatus(ArchitectStatus.DONE);
        }));

        ParentReport report = engine.summarizeForParent(
                process, ProcessEventType.DONE);

        assertThat(report.humanSummary())
                .contains("plan-only")
                .contains("essay-plan")
                .contains("recipes/_slart/abc/essay-plan.yaml")
                .doesNotContain("ran it to completion");
    }

    @Test
    void done_withoutOutputPaths_notesAbsence() {
        // Some recipes (chat-only flows) have no path-output criteria.
        // The summary should still be useful — just not promise files
        // that don't exist.
        ThinkProcessDocument process = process(state(s -> {
            s.setProposedRecipe(RecipeDraft.builder()
                    .name("quick-analysis").build());
            s.setPersistedRecipePath("recipes/_slart/x/quick-analysis.yaml");
            s.setChildExecutionOutcome("DONE");
            s.setStatus(ArchitectStatus.DONE);
        }));

        ParentReport report = engine.summarizeForParent(
                process, ProcessEventType.DONE);

        assertThat(report.humanSummary())
                .contains("ran it to completion")
                .contains("declared no path-output criteria")
                .doesNotContain("Outputs written to:");
    }

    @Test
    void failed_propagatesFailureReason() {
        ThinkProcessDocument process = process(state(s -> {
            s.setProposedRecipe(RecipeDraft.builder().name("broken-recipe").build());
            s.setStatus(ArchitectStatus.FAILED);
            s.setFailureReason("PROPOSING exhausted 10 recoveries");
            s.setChildExecutionOutcome("FAILED");
        }));

        ParentReport report = engine.summarizeForParent(
                process, ProcessEventType.FAILED);

        assertThat(report.humanSummary())
                .contains("Slartibartfast failed")
                .contains("broken-recipe")
                .contains("PROPOSING exhausted 10 recoveries")
                .contains("Child execution outcome: FAILED");
        assertThat(report.payload())
                .containsEntry("eventType", "FAILED")
                .containsEntry("failureReason", "PROPOSING exhausted 10 recoveries");
    }

    @Test
    void stopped_minimalSummary() {
        ThinkProcessDocument process = process(state(s -> {
            s.setProposedRecipe(RecipeDraft.builder().name("essay-x").build());
            // No ArchitectStatus.STOPPED — STOPPED is a lifecycle
            // event on the parent's side, mapped from CloseReason.
            // Leave the architect-status whatever it was when the
            // stop came in.
        }));

        ParentReport report = engine.summarizeForParent(
                process, ProcessEventType.STOPPED);

        assertThat(report.humanSummary())
                .contains("Slartibartfast was stopped")
                .contains("essay-x");
    }

    @Test
    void blocked_namesAwaitedInputKind() {
        ThinkProcessDocument process = process(state(s -> {
            s.setStatus(ArchitectStatus.CONFIRMING);
            s.setPendingInboxKind(
                    de.mhus.vance.api.slartibartfast.PendingInboxKind.CONFIRMATION);
        }));

        ParentReport report = engine.summarizeForParent(
                process, ProcessEventType.BLOCKED);

        assertThat(report.humanSummary())
                .contains("blocked")
                .contains("confirming")
                .contains("confirmation");
    }

    @Test
    void corruptState_failsSoftWithoutThrowing() {
        // engineParams holds something that isn't a valid ArchitectState
        // serialisation. Don't crash the listener — emit a generic
        // fallback. Pre-fix Slart would just hit the default
        // "status=done" path here, so a benign degenerate is acceptable.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("proc-corrupt");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(SlartibartfastEngine.STATE_KEY, "not-a-state");
        process.setEngineParams(params);

        ParentReport report = engine.summarizeForParent(
                process, ProcessEventType.DONE);

        assertThat(report).isNotNull();
        assertThat(report.humanSummary())
                .containsAnyOf("proc-corrupt", "Slartibartfast")
                .contains("done");
    }

    // ──────────────────── helpers ────────────────────

    private ThinkProcessDocument process(ArchitectState state) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("proc-1");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(SlartibartfastEngine.STATE_KEY,
                objectMapper.convertValue(state, Map.class));
        p.setEngineParams(params);
        return p;
    }

    private static ArchitectState state(java.util.function.Consumer<ArchitectState> mutate) {
        ArchitectState s = ArchitectState.builder().build();
        s.setRunId("4f31b631");
        s.setOutputSchemaType(OutputSchemaType.VOGON_STRATEGY);
        s.setAcceptanceCriteria(new ArrayList<>());
        mutate.accept(s);
        return s;
    }

    private static Criterion pathCriterion(String path) {
        return Criterion.builder()
                .id("cr-" + Math.abs(path.hashCode()))
                .text("The recipe must persist its output at `" + path
                        + "` via doc_write_text.")
                .origin(CriterionOrigin.INFERRED_DOMAIN)
                .confidence(0.8)
                .build();
    }
}
