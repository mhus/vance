package de.mhus.vance.brain.slartibartfast;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.ClassificationKind;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.EvidenceSource;
import de.mhus.vance.api.slartibartfast.EvidenceType;
import de.mhus.vance.api.slartibartfast.FramedGoal;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Rationale;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.TerminationRationale;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M1 phase implementations — deterministic stubs that exercise the
 * full state shape (rationales, iterations, criterion split) without
 * calling an LLM. Lets the lifecycle (status transitions, state
 * persistence, terminal handling, audit append-only invariants) be
 * tested end-to-end before plugging in real phase logic in M2-M4.
 *
 * <p>Each stub appends one {@link PhaseIteration} marked
 * {@link PhaseIteration.IterationOutcome#PASSED} so the audit log
 * mirrors what a real run would produce — just with placeholder
 * inputs/outputs and no LLM-call records (none of the stubs issues
 * an LLM call).
 */
final class SlartibartfastPhases {

    private SlartibartfastPhases() {}

    /** Fill {@link ArchitectState#getGoal()} with a trivial framing —
     *  one stated criterion, one inferred-convention criterion
     *  (high-confidence, will pass through CONFIRMING). */
    static void stubFraming(ArchitectState state) {
        Rationale conv = appendRationale(state,
                "rt-stub-conv",
                "[stub] convention: produced content gets persisted",
                List.of(),
                ArchitectStatus.FRAMING);

        Criterion stated = Criterion.builder()
                .id("cr1")
                .text("[stub] stated criterion derived from user text")
                .origin(CriterionOrigin.USER_STATED)
                .confidence(1.0)
                .build();

        Criterion assumed = Criterion.builder()
                .id("cr2")
                .text("[stub] result is persisted as a document")
                .origin(CriterionOrigin.INFERRED_CONVENTION)
                .confidence(0.95)
                .rationaleId(conv.getId())
                .build();

        FramedGoal goal = FramedGoal.builder()
                .framed("[stub] " + state.getUserDescription())
                .sourceUserText(state.getUserDescription())
                .statedCriteria(List.of(stated))
                .assumedCriteria(List.of(assumed))
                .build();
        state.setGoal(goal);

        appendIteration(state, ArchitectStatus.FRAMING,
                "user-description=" + abbrev(state.getUserDescription(), 60),
                "1 stated, 1 assumed (conf=0.95)");
    }

    /** Stub CONFIRMING: high-confidence assumed criterion → audit
     *  pass-through, no inbox question. Real CONFIRMING (M2) splits
     *  by confidence and posts an inbox item for low-confidence ones. */
    static void stubConfirming(ArchitectState state) {
        // Stub doesn't transform criteria — they pass through as
        // emitted by FRAMING. Real impl flips low-confidence ones
        // to USER_CONFIRMED after inbox response.
        appendIteration(state, ArchitectStatus.CONFIRMING,
                summariseAssumedConfidence(state),
                "[stub] all assumed criteria passed through (high-conf)");
    }

    /** Append one synthetic evidence source with a gathering
     *  rationale. Real GATHERING (M2) calls manual_list / manual_read
     *  and records why each source was consulted. */
    static void stubGathering(ArchitectState state) {
        Rationale gather = appendRationale(state,
                "rt-stub-gather",
                "[stub] no real manuals consulted; default fallback source",
                List.of(),
                ArchitectStatus.GATHERING);

        EvidenceSource source = EvidenceSource.builder()
                .id("ev1")
                .type(EvidenceType.DEFAULT)
                .path(null)
                .content("[stub] no manuals consulted")
                .gatheringRationaleId(gather.getId())
                .build();
        List<EvidenceSource> sources = new ArrayList<>(state.getEvidenceSources());
        sources.add(source);
        state.setEvidenceSources(sources);

        appendIteration(state, ArchitectStatus.GATHERING,
                "0 manuals to consult",
                "1 fallback source");
    }

    /** Append one synthetic claim. Real CLASSIFYING (M3) tags
     *  every paragraph of every source as
     *  FACT/EXAMPLE/OPINION/OUTDATED with per-claim rationale. */
    static void stubClassifying(ArchitectState state) {
        Rationale classRat = appendRationale(state,
                "rt-stub-class",
                "[stub] default OPINION since no real classification done",
                List.of("ev1"),
                ArchitectStatus.CLASSIFYING);

        Claim claim = Claim.builder()
                .id("cl1")
                .sourceId("ev1")
                .text("[stub] no real classification performed")
                .classification(ClassificationKind.OPINION)
                .classificationRationaleId(classRat.getId())
                .build();
        List<Claim> claims = new ArrayList<>(state.getEvidenceClaims());
        claims.add(claim);
        state.setEvidenceClaims(claims);

        appendIteration(state, ArchitectStatus.CLASSIFYING,
                state.getEvidenceSources().size() + " sources",
                "1 OPINION claim");
    }

    /** Append one trivially-supported subgoal + decomposition
     *  rationale. Real DECOMPOSING (M3) produces N subgoals tied
     *  to claims and an overall rationale for the decomposition
     *  shape. */
    static void stubDecomposing(ArchitectState state) {
        Rationale decompRat = appendRationale(state,
                "rt-stub-decomp",
                "[stub] single placeholder subgoal — real planner emits N",
                List.of(),
                ArchitectStatus.DECOMPOSING);
        state.setDecompositionRationaleId(decompRat.getId());

        Subgoal subgoal = Subgoal.builder()
                .id("sg1")
                .goal("[stub] subgoal placeholder")
                .evidenceRefs(List.of("cl1"))
                .criterionRefs(List.of("cr1"))
                .speculative(false)
                .build();
        List<Subgoal> subgoals = new ArrayList<>(state.getSubgoals());
        subgoals.add(subgoal);
        state.setSubgoals(subgoals);

        appendIteration(state, ArchitectStatus.DECOMPOSING,
                state.getEvidenceClaims().size() + " claims, "
                        + countCriteria(state) + " criteria",
                "1 subgoal (non-speculative)");
    }

    /** Always-pass binding check. Real BINDING (M3) enforces:
     *  every subgoal evidenceRefs non-empty OR speculative+rationale,
     *  and routes failures via {@link de.mhus.vance.api.slartibartfast.RecoveryRequest}. */
    static void stubBinding(ArchitectState state) {
        ValidationCheck check = ValidationCheck.builder()
                .rule("every-subgoal-has-evidence-or-marked-speculative")
                .passed(true)
                .message("[stub] not validated")
                .build();
        state.setValidationReport(List.of(check));

        appendIteration(state, ArchitectStatus.BINDING,
                state.getSubgoals().size() + " subgoals",
                "[stub] always-pass");
    }

    /** Stub recipe draft — minimal valid YAML, no real planning. */
    static void stubProposing(ArchitectState state) {
        Rationale shape = appendRationale(state,
                "rt-stub-shape",
                "[stub] minimal one-phase shape — real planner picks template",
                List.of(),
                ArchitectStatus.PROPOSING);

        Map<String, String> just = new LinkedHashMap<>();
        just.put("name", "sg1");
        RecipeDraft draft = RecipeDraft.builder()
                .name("stub-recipe")
                .outputSchemaType(state.getOutputSchemaType())
                .yaml("name: stub-recipe\ndescription: |\n  M1 stub output.\nengine: vogon\n")
                .justifications(just)
                .confidence(speculativeRatio(state) < 0.3 ? 0.5 : 0.3)
                .warnings("[stub] generated without real LLM planning")
                .shapeRationaleId(shape.getId())
                .build();
        state.setProposedRecipe(draft);

        appendIteration(state, ArchitectStatus.PROPOSING,
                "subgoals=" + state.getSubgoals().size(),
                "1 recipe-draft, conf=" + draft.getConfidence());
    }

    /** Always-pass validation. Real VALIDATING (M4) runs:
     *  shape check, referential integrity, speculation-bound,
     *  recipe-parser sanity. */
    static void stubValidating(ArchitectState state) {
        ValidationCheck check = ValidationCheck.builder()
                .rule("recipe-yaml-parses")
                .passed(true)
                .message("[stub] not validated")
                .build();
        List<ValidationCheck> report = new ArrayList<>(state.getValidationReport());
        report.add(check);
        state.setValidationReport(report);

        appendIteration(state, ArchitectStatus.VALIDATING,
                "draft.confidence="
                        + (state.getProposedRecipe() == null
                                ? "n/a" : state.getProposedRecipe().getConfidence()),
                "[stub] always-pass");
    }

    /** Records a synthetic recipe path + termination rationale
     *  without actually writing the document. Real PERSISTING (M4)
     *  writes the recipe YAML and audit.json to the
     *  {@code _slart/<runId>/} bucket. */
    static void stubPersisting(ArchitectState state) {
        String path = "recipes/_slart/" + state.getRunId() + "/stub-recipe.yaml";
        state.setPersistedRecipePath(path);

        TerminationRationale term = TerminationRationale.builder()
                .passedChecks(state.getValidationReport().stream()
                        .filter(ValidationCheck::isPassed)
                        .map(ValidationCheck::getRule).toList())
                .statedCriteriaSatisfied(state.getGoal() == null
                        ? List.of()
                        : state.getGoal().getStatedCriteria().stream()
                                .map(Criterion::getId).toList())
                .assumedCriteriaTakenForGranted(state.getGoal() == null
                        ? List.of()
                        : state.getGoal().getAssumedCriteria().stream()
                                .filter(c -> c.getConfidence()
                                        >= state.getConfirmationThreshold())
                                .map(Criterion::getId).toList())
                .evidenceCoverage(1.0 - speculativeRatio(state))
                .iterationCount(state.getIterations().size() + 1)
                .recoveryEvents(state.getRecoveryCount())
                .finalConfidence(state.getProposedRecipe() == null
                        ? 0.0 : state.getProposedRecipe().getConfidence())
                .build();
        state.setTerminationRationale(term);

        appendIteration(state, ArchitectStatus.PERSISTING,
                "recipe-draft ready",
                "wrote " + path);
    }

    // ──────────────────── helpers ────────────────────

    /**
     * Append a rationale to the pool with a stable, sequential id.
     * The conventional shape is {@code "rt<n>"}; in the stub we
     * suffix the caller-provided base with the current pool size
     * so successive appendRationale calls in the same iteration
     * don't collide.
     */
    private static Rationale appendRationale(
            ArchitectState state,
            String idBase,
            String text,
            List<String> sourceRefs,
            ArchitectStatus inferredAt) {
        String id = idBase + "-" + (state.getRationales().size() + 1);
        Rationale r = Rationale.builder()
                .id(id)
                .text(text)
                .sourceRefs(new ArrayList<>(sourceRefs))
                .inferredAt(inferredAt)
                .build();
        List<Rationale> pool = new ArrayList<>(state.getRationales());
        pool.add(r);
        state.setRationales(pool);
        return r;
    }

    private static void appendIteration(
            ArchitectState state,
            ArchitectStatus phase,
            String inputSummary,
            String outputSummary) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == phase).count() + 1;
        PhaseIteration it = PhaseIteration.builder()
                .iteration(attempt)
                .phase(phase)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(PhaseIteration.IterationOutcome.PASSED)
                .build();
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(it);
        state.setIterations(log);
    }

    private static int countCriteria(ArchitectState state) {
        if (state.getGoal() == null) return 0;
        return state.getGoal().getStatedCriteria().size()
                + state.getGoal().getAssumedCriteria().size();
    }

    private static double speculativeRatio(ArchitectState state) {
        List<Subgoal> subgoals = state.getSubgoals();
        if (subgoals.isEmpty()) return 0.0;
        long speculative = subgoals.stream().filter(Subgoal::isSpeculative).count();
        return (double) speculative / subgoals.size();
    }

    private static String summariseAssumedConfidence(ArchitectState state) {
        if (state.getGoal() == null) return "no goal yet";
        long high = state.getGoal().getAssumedCriteria().stream()
                .filter(c -> c.getConfidence() >= state.getConfirmationThreshold())
                .count();
        long low = state.getGoal().getAssumedCriteria().size() - high;
        return high + " high-conf, " + low + " low-conf assumed";
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
