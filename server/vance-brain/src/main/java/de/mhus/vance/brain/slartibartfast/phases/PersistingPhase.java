package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.TerminationRationale;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.slartibartfast.architect.SchemaArchitect;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * PERSISTING phase — writes the validated {@link RecipeDraft} as a
 * Document under
 * {@code recipes/_slart/<runId>/<recipe-name>.yaml} (per spec §3a)
 * plus a sibling
 * {@code recipes/_slart/<runId>/audit.json} containing the full
 * {@link ArchitectState} for post-hoc inspection. Builds the
 * {@link TerminationRationale} that PERSISTING records on the
 * state and the DONE-payload exposes.
 *
 * <p>Idempotent — re-runs find-or-update both documents. Stable
 * paths (deterministic on runId + recipe name) so a re-run after
 * recovery doesn't create orphan documents.
 *
 * <p>This is the final productive phase before DONE. After PERSISTING
 * runs successfully, the engine flips status to
 * {@link ArchitectStatus#DONE} and closes the process.
 */
@Component
@Slf4j
public class PersistingPhase {

    public static final String SLART_PREFIX = "_vance/recipes/_slart/";
    public static final String USER_PREFIX = "_vance/recipes/_user/";

    private final DocumentService documentService;
    private final ObjectMapper objectMapper;
    /** Architect lookup — same construction pattern as ValidatingPhase
     *  / ProposingPhase. Used to read the per-schema output-path
     *  segment and extension for non-recipe schemas (e.g. SCRIPT_JS
     *  writes to {@code _vance/scripts/_slart/<runId>/<name>.js}). */
    private final Map<OutputSchemaType, SchemaArchitect> architects;

    public PersistingPhase(
            DocumentService documentService,
            ObjectMapper objectMapper,
            List<SchemaArchitect> schemaArchitects) {
        this.documentService = documentService;
        this.objectMapper = objectMapper;
        Map<OutputSchemaType, SchemaArchitect> map = new EnumMap<>(OutputSchemaType.class);
        for (SchemaArchitect a : schemaArchitects) {
            SchemaArchitect existing = map.put(a.type(), a);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate SchemaArchitect beans for "
                                + a.type() + ": "
                                + existing.getClass().getName()
                                + " and " + a.getClass().getName());
            }
        }
        this.architects = Map.copyOf(map);
    }

    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        RecipeDraft draft = state.getProposedRecipe();
        if (draft == null) {
            state.setFailureReason("PERSISTING entered without a "
                    + "proposedRecipe — VALIDATING must run first");
            return;
        }

        String runId = state.getRunId();
        if (runId == null || runId.isBlank()) {
            state.setFailureReason("PERSISTING entered with empty runId");
            return;
        }

        // Resolve the architect for this run's output schema so we
        // know where the artefact lives (recipes vs. scripts) and
        // what extension to use. Recipe schemas keep the legacy
        // _vance/recipes/{_slart,_user}/... layout; non-recipe
        // schemas (SCRIPT_JS) write to _vance/<segment>/_slart/...
        SchemaArchitect architect = architects.get(state.getOutputSchemaType());
        if (architect == null) {
            state.setFailureReason("PERSISTING has no SchemaArchitect "
                    + "bean for " + state.getOutputSchemaType());
            return;
        }
        String segment = architect.outputPathSegment();
        String extension = architect.outputExtension();
        String slartPrefix = "_vance/" + segment + "/_slart/";
        String userPrefix = "_vance/" + segment + "/_user/";

        // Phase-D write-path resolution:
        //   EDIT (recipe only)   → <userPrefix><targetRecipeName>.yaml (overwrite)
        //   CREATE + recipeName  → <userPrefix><recipeName>.yaml       (named upsert; recipes only)
        //   CREATE anonymous     → <slartPrefix><runId>/<draft.name><ext> (sandbox bucket)
        //
        // EDIT and named-CREATE are recipe-only concepts; non-recipe
        // schemas (SCRIPT_JS) always land in the slart sandbox bucket
        // in Phase 2a. UPDATE-mode for scripts (Phase 2b) will reuse
        // the slart bucket — different runId per call, no in-place
        // overwrite (planning §5.1 + Slart §8 garantie).
        String recipePath;
        String auditPath;
        boolean isUserNamespace;
        boolean recipeMode = architect.isRecipeOutput();
        if (recipeMode
                && state.getMode() == de.mhus.vance.api.slartibartfast.ArchitectMode.EDIT) {
            String name = state.getTargetRecipeName();
            if (name == null || name.isBlank()) {
                state.setFailureReason("PERSISTING in EDIT mode without "
                        + "targetRecipeName — LOADING_EXISTING must set it");
                return;
            }
            recipePath = userPrefix + name + extension;
            auditPath = userPrefix + name + ".audit.json";
            isUserNamespace = true;
        } else if (recipeMode
                && state.getRecipeName() != null
                && !state.getRecipeName().isBlank()) {
            String name = state.getRecipeName();
            recipePath = userPrefix + name + extension;
            auditPath = userPrefix + name + ".audit.json";
            isUserNamespace = true;
            // Named CREATE upserts — when "speicher unter 'X'"
            // hits an existing X, we treat the new run as the
            // canonical version and overwrite. The previous
            // versions are preserved by the DocumentService
            // version layer (planned), or by manual deletion
            // if the user wants a fresh slate. Log the
            // overwrite so the operation is visible in audit.
            if (documentService.findByPath(
                    process.getTenantId(), process.getProjectId(), recipePath)
                    .isPresent()) {
                log.info("Slartibartfast id='{}' PERSISTING — overwriting "
                                + "existing recipe at '{}' (named CREATE)",
                        process.getId(), recipePath);
            }
        } else {
            recipePath = slartPrefix + runId + "/" + draft.getName() + extension;
            auditPath = slartPrefix + runId + "/audit.json";
            isUserNamespace = false;
        }

        String artefactNoun = recipeMode ? "recipe" : "script";
        try {
            writeOrUpdate(process, recipePath, draft.getYaml(),
                    isUserNamespace
                            ? "Slartibartfast user-namespace "
                                    + artefactNoun + " '"
                                    + (state.getMode()
                                            == de.mhus.vance.api.slartibartfast.ArchitectMode.EDIT
                                            ? state.getTargetRecipeName()
                                            : state.getRecipeName())
                                    + "'"
                            : "Slartibartfast-generated "
                                    + artefactNoun + " for run " + runId);
        } catch (RuntimeException e) {
            state.setFailureReason("PERSISTING failed writing "
                    + artefactNoun + " " + recipePath
                    + ": " + e.getMessage());
            appendIteration(state,
                    "draft '" + draft.getName() + "'",
                    "FAILED — " + artefactNoun + " write error: "
                            + e.getMessage(),
                    PhaseIteration.IterationOutcome.FAILED);
            return;
        }

        // Build the termination rationale BEFORE the audit dump so
        // the dump includes the rationale.
        TerminationRationale termination = buildTerminationRationale(state);
        state.setTerminationRationale(termination);
        state.setPersistedRecipePath(recipePath);

        try {
            String auditJson = objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(state);
            writeOrUpdate(process, auditPath, auditJson,
                    "Audit chain for Slartibartfast run " + runId);
        } catch (RuntimeException e) {
            // Audit write failed — recipe is already persisted, so
            // the run is effectively successful. Log the audit
            // miss but don't fail the phase.
            log.warn("Slartibartfast id='{}' PERSISTING wrote recipe but "
                            + "audit.json failed: {}",
                    process.getId(), e.toString());
        }

        appendIteration(state,
                "draft '" + draft.getName() + "'",
                "wrote " + recipePath + " (+ audit.json)",
                PhaseIteration.IterationOutcome.PASSED);

        log.info("Slartibartfast id='{}' PERSISTING wrote {} at '{}' "
                        + "with confidence {}, {} iterations, {} recoveries",
                process.getId(), artefactNoun, recipePath, draft.getConfidence(),
                termination.getIterationCount(),
                termination.getRecoveryEvents());
    }

    // ──────────────────── Document write ────────────────────

    /**
     * Idempotent create-or-update for a document at the given
     * project-relative path. Same find-then-update / else-create
     * pattern Vogon's BranchActionExecutor uses for postActions.
     */
    private void writeOrUpdate(
            ThinkProcessDocument process,
            String path,
            String content,
            String title) {
        String tenantId = process.getTenantId();
        String projectId = process.getProjectId();
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(),
                    title, /*tags*/ null, content, /*newPath*/ null);
        } else {
            documentService.createText(
                    tenantId, projectId, path,
                    title, /*tags*/ null, content,
                    /*createdBy*/ "slartibartfast:" + process.getId());
        }
    }

    // ──────────────────── TerminationRationale ────────────────────

    private static TerminationRationale buildTerminationRationale(ArchitectState state) {
        // 1. Which validation checks ultimately passed.
        List<String> passedChecks = state.getValidationReport().stream()
                .filter(ValidationCheck::isPassed)
                .map(ValidationCheck::getRule)
                .distinct()
                .toList();

        // 2. Stated criteria satisfied — every USER_STATED entry
        //    in acceptanceCriteria that some subgoal addresses.
        List<String> statedSatisfied = new ArrayList<>();
        List<String> assumedTakenForGranted = new ArrayList<>();
        List<String> assumedUserConfirmed = new ArrayList<>();
        Map<String, List<String>> coverage = new LinkedHashMap<>();
        for (Criterion c : state.getAcceptanceCriteria()) {
            List<String> coveringSgIds = new ArrayList<>();
            for (Subgoal sg : state.getSubgoals()) {
                if (sg.getCriterionRefs().contains(c.getId())) {
                    coveringSgIds.add(sg.getId());
                }
            }
            coverage.put(c.getId(), coveringSgIds);
            if (coveringSgIds.isEmpty()) continue;
            switch (c.getOrigin()) {
                case USER_STATED -> statedSatisfied.add(c.getId());
                case USER_CONFIRMED -> assumedUserConfirmed.add(c.getId());
                case INFERRED_CONVENTION,
                        INFERRED_DOMAIN,
                        INFERRED_CONTEXT,
                        DEFAULT -> assumedTakenForGranted.add(c.getId());
            }
        }

        // 3. Speculation ratio.
        double evidenceCoverage = 1.0;
        if (!state.getSubgoals().isEmpty()) {
            long speculative = state.getSubgoals().stream()
                    .filter(Subgoal::isSpeculative).count();
            evidenceCoverage = 1.0 - ((double) speculative / state.getSubgoals().size());
        }

        // 4. Final confidence.
        double finalConfidence = state.getProposedRecipe() == null
                ? 0.0 : state.getProposedRecipe().getConfidence();

        return TerminationRationale.builder()
                .passedChecks(passedChecks)
                .statedCriteriaSatisfied(statedSatisfied)
                .assumedCriteriaTakenForGranted(assumedTakenForGranted)
                .assumedCriteriaUserConfirmed(assumedUserConfirmed)
                // M6 will populate userRejected via inbox dialog;
                // for now it stays empty.
                .assumedCriteriaUserRejected(List.of())
                .criterionCoverage(coverage)
                .evidenceCoverage(evidenceCoverage)
                .iterationCount(state.getIterations().size())
                .recoveryEvents(state.getRecoveryCount())
                .finalConfidence(finalConfidence)
                .build();
    }

    // ──────────────────── Audit append ────────────────────

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.PERSISTING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.PERSISTING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }
}
