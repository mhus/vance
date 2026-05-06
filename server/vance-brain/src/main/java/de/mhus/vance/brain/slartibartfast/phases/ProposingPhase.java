package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.LlmCallRecord;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Rationale;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * PROPOSING phase — turns the bound subgoals + framed goal into a
 * concrete recipe YAML. For
 * {@link OutputSchemaType#VOGON_STRATEGY} the output is a recipe
 * with {@code engine: vogon} and an inline
 * {@code params.strategyPlanYaml} describing the phases. The
 * detailed Vogon-strategy schema (phases, gates, scorers,
 * postActions, …) is defined in {@code specification/vogon-engine.md}.
 *
 * <p>Justifications: every recipe-level constraint or phase
 * decision must reference the {@link Subgoal#getId()} that
 * motivates it — the audit trail VALIDATING uses to reject
 * references to ghost ids.
 *
 * <p>Marvin-recipe output is a v2 concern (the recipe shape is
 * different — params.allowedSubTaskRecipes etc instead of a
 * strategyPlanYaml). Slartibartfast accepts the request via
 * {@code outputSchemaType=MARVIN_RECIPE} but currently emits a
 * placeholder draft and lets VALIDATING flag it as unsupported.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProposingPhase {

    private static final String ENGINE_NAME = "slartibartfast";
    private static final int MAX_OUTPUT_CORRECTIONS = 2;
    private static final int PROMPT_PREVIEW_LIMIT = 500;

    private static final String SYSTEM_PROMPT_MARVIN = """
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal, the subgoals, and the list of
            available sub-recipes, you produce a Marvin recipe.
            Marvin's PLAN validator enforces your constraints at
            runtime — if you omit them the runtime PLAN-LLM takes
            shortcuts and the pipeline does not run through.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<recipe-name, kebab-case>",
                  "yaml":           "<full recipe YAML>",
                  "justifications": {
                    "params.allowedSubTaskRecipes": "<sg-id>",
                    "promptPrefix":                 "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this shape — 1-2 sentences>"
                }

            ── COMPLETENESS REQUIREMENT ──

            Your recipe MUST drive the user's request to a final
            deliverable, not just one stage of it. If the user
            asks for an essay, the recipe MUST run outline →
            chapter writing → aggregation (final consolidation).
            Producing only the outline phase, only chapters, or
            stopping before aggregation is a hard failure: the
            user gets no usable result and the validator will
            reject the recipe.

            When the available sub-recipes include outline-style,
            chapter-style, AND aggregator-style entries, you MUST
            wire all three into allowedSubTaskRecipes and the
            promptPrefix. Pick fewer phases ONLY when the user's
            request truly needs less (e.g. they explicitly asked
            for an outline only).

            ── MANDATORY CONSTRAINTS (set when applicable) ──

            These constraints are NOT optional when the inputs
            motivate them. Slartibartfast detects motivated
            constraints from the subgoals + the available sub-recipes:

            **allowedSubTaskRecipes** — REQUIRED whenever the
              subgoals map to concrete sub-recipes. Inspect the
              "Available sub-recipes" block in the user prompt. A
              subgoal that "writes plot/cast/outline" maps to an
              outline-style recipe; "writes chapters" to a
              chapter-style recipe; "consolidates/aggregates" to an
              aggregator-style recipe. List EXACTLY the recipes
              your plan phases will use.

            **recipesOnlyViaExpand** — REQUIRED when a subgoal
              iterates "per item in a document". List the recipes
              that may appear ONLY inside an EXPAND_FROM_DOC
              childTemplate (typical: chapter-loop for
              chapter-per-outline-item).

            **allowedExpandDocumentRefPaths** — REQUIRED when you
              set recipesOnlyViaExpand. List the document paths the
              EXPAND_FROM_DOC iterates over (e.g.
              "essay/outline.md").

            **disallowedTaskKinds** — Set [AGGREGATE] when your
              plan shape needs a WORKER aggregator (instead of
              Marvin's built-in AGGREGATE summary). Standard for
              pipelines with an aggregator-style recipe.

            **defaultExecutionMode: SEQUENTIAL** — Default when the
              plan phases build on each other (outline → chapters →
              aggregator). Use PARALLEL only when phases are
              independent.

            **maxPlanCorrections: 2** — Default. Omit only for
              extremely conservative use cases.

            ── promptPrefix CONTRACT (the runtime PLAN-LLM reads it) ──

            **CRITICAL — KIND-block parity rule:**
              The number of KIND blocks in your promptPrefix MUST
              equal the size of allowedSubTaskRecipes (and the
              number of children Marvin's PLAN should emit).
              EVERY recipe in allowedSubTaskRecipes MUST have
              exactly one KIND block referencing it (either as a
              direct WORKER taskSpec.recipe, or — for entries in
              recipesOnlyViaExpand — as the EXPAND_FROM_DOC
              childTemplate.recipe). If you list 3 recipes you MUST
              write 3 KIND blocks; the runtime LLM otherwise drops
              the trailing ones.

            **JSON skeleton rule:** Each KIND block MUST contain a
              concrete JSON skeleton for that child (literal
              taskKind / goal / taskSpec). Plain prose without a
              JSON sample lets the LLM omit the child.

            **Order rule:** The KIND blocks MUST appear in
              execution order (the order Marvin will use under
              SEQUENTIAL). The order also reflects the
              data-dependency chain (a phase that consumes
              `essay/outline.md` comes after the phase that
              produces it).

            **No manual fan-out:** "Iterate per item in a document"
              ALWAYS means EXPAND_FROM_DOC with documentRef +
              childTemplate. Never enumerate items by hand.

            ── YAML structure ──

                name: <name, same as above>
                description: |
                  <1-2 sentences>
                engine: marvin
                params:
                  rootTaskKind: PLAN
                  maxPlanCorrections: 2
                  defaultExecutionMode: SEQUENTIAL
                  allowedSubTaskRecipes:
                    - <recipe1-name>
                    - <recipe2-name>
                  recipesOnlyViaExpand:
                    - <chapter-loop-name>
                  allowedExpandDocumentRefPaths:
                    - <e.g. essay/outline.md>
                  disallowedTaskKinds: [AGGREGATE]
                promptPrefix: |
                  You are the `<name>` PLAN node. Emit EXACTLY N
                  children in this exact order. N MUST equal the
                  number of recipes in allowedSubTaskRecipes.

                  KIND 1 — <description matching subgoal sg1>
                  <one-line literal JSON skeleton for child 1>

                  KIND 2 — <description matching sg2>
                  <one-line literal JSON skeleton for child 2>

                  ...

                  Output contract — ONLY these N children:
                      {"children": [<KIND 1>, <KIND 2>, ...]}

                  Do not omit any KIND. Do not add extras. The
                  number of children MUST be exactly N.

            ── EXAMPLE (essay-style pipeline, N=3) ──

                name: my-essay-pipeline
                description: |
                  Produces an essay through outline → chapters → aggregation.
                engine: marvin
                params:
                  rootTaskKind: PLAN
                  maxPlanCorrections: 2
                  defaultExecutionMode: SEQUENTIAL
                  allowedSubTaskRecipes:
                    - outline_loop
                    - chapter_loop
                    - aggregator_run
                  recipesOnlyViaExpand:
                    - chapter_loop
                  allowedExpandDocumentRefPaths:
                    - essay/outline.md
                  disallowedTaskKinds: [AGGREGATE]
                promptPrefix: |
                  You are the my-essay-pipeline PLAN node. Emit
                  EXACTLY 3 children, one per recipe in
                  allowedSubTaskRecipes [outline_loop, chapter_loop,
                  aggregator_run], in this exact order:

                  KIND 1 — WORKER outline_loop (produces essay/outline.md):
                  {"taskKind":"WORKER","goal":"Draft plot, cast, and outline.","taskSpec":{"recipe":"outline_loop"}}

                  KIND 2 — EXPAND_FROM_DOC over outline (one chapter per item):
                  {"taskKind":"EXPAND_FROM_DOC","goal":"Run one chapter_loop per outline item.",
                   "taskSpec":{"documentRef":{"path":"essay/outline.md"},
                               "treeMode":"FLAT",
                               "childTemplate":{"taskKind":"WORKER","recipe":"chapter_loop","goal":"Write the chapter."}}}

                  KIND 3 — WORKER aggregator_run (consolidates chapters → final-essay.md):
                  {"taskKind":"WORKER","goal":"Consolidate chapters into the final essay and post the inbox notification.","taskSpec":{"recipe":"aggregator_run"}}

                  Output contract — EXACTLY these 3 children, no fewer:
                      {"children":[<KIND 1>,<KIND 2>,<KIND 3>]}

                  Do not omit KIND 3. The number of children MUST be 3.

            ── Language ──

            The promptPrefix you generate MUST be in English (the
            runtime PLAN-LLM reads it as orchestration code). The
            user-facing content language is carried separately by
            the goal text and is not your concern here.

            ── justifications map ──

            Every constraint-key you set in the YAML (params.X or
            promptPrefix) MUST point to an sg-id that exists in
            the subgoal list.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    private static final String SYSTEM_PROMPT_VOGON = """
            You are the PROPOSING node of the Slartibartfast engine.
            From the framed goal and the subgoals you produce an
            executable recipe for the Vogon engine. The recipe
            wraps an inline strategyPlanYaml (Vogon strategy).

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema:
                {
                  "name":           "<recipe-name, kebab-case>",
                  "yaml":           "<full recipe YAML, see structure below>",
                  "justifications": {
                    "<constraint-key>": "<sg-id>",
                    "phases.0.worker": "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this shape — 1-2 sentences>"
                }

            YAML structure (mandatory):
                name: <name, same as above>
                description: |
                  <1-2 sentences>
                engine: vogon
                params:
                  strategyPlanYaml: |
                    name: <strategy-name>
                    version: "1"
                    phases:
                      - name: <phase-name>
                        worker: <recipe-name or ford>
                        workerInput: |
                          <prompt for the worker>
                        gate: { requires: [<phase-name>_completed] }

            justifications map (mandatory):
            - EVERY constraint-key you set in the YAML MUST point
              here to an sg-id that exists in subgoals.
            - Convention for constraint-keys:
              - "name" for the recipe name
              - "phases.<idx>.worker" for each phase
              - "engine" if you pick anything other than "vogon"
                (should never happen here — output schema type is
                VOGON_STRATEGY)

            confidence:
            - 1.0 minus the speculative share = a coarse heuristic
            - VALIDATING will check this.

            shapeRationale: WHY this exact number of phases in
            this order. Refers to the overall plan shape, not
            individual phases.

            Language: workerInput and prose-style fields are read
            by downstream LLMs as orchestration code — write them
            in English. The user-facing content language is
            carried separately by the goal text.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final ObjectMapper objectMapper;
    private final RecipeLoader recipeLoader;

    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        if (state.getGoal() == null || state.getSubgoals().isEmpty()) {
            state.setFailureReason("PROPOSING entered without goal/subgoals — "
                    + "DECOMPOSING+BINDING must run first");
            return;
        }

        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, ENGINE_NAME);
        String modelAlias = bundle.primaryConfig().provider() + ":"
                + bundle.primaryConfig().modelName();

        // Recovery hint from a failed VALIDATING pass.
        String recoveryHint = null;
        if (state.getPendingRecovery() != null
                && state.getPendingRecovery().getToPhase() == ArchitectStatus.PROPOSING) {
            recoveryHint = state.getPendingRecovery().getHint();
        }

        String systemPrompt = switch (state.getOutputSchemaType()) {
            case VOGON_STRATEGY -> SYSTEM_PROMPT_VOGON;
            case MARVIN_RECIPE -> SYSTEM_PROMPT_MARVIN;
        };

        // For MARVIN_RECIPE: list the project's currently-available
        // sub-recipes so the LLM has concrete names to put into
        // allowedSubTaskRecipes. Without this list it tends to
        // omit the constraint and the runtime PLAN takes shortcuts.
        List<ResolvedRecipe> availableRecipes =
                state.getOutputSchemaType() == OutputSchemaType.MARVIN_RECIPE
                        ? listAvailableSubRecipes(process)
                        : List.of();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(
                buildInitialUserPrompt(state, recoveryHint, availableRecipes)));

        ProposeResult parsed = null;
        String validationError = null;
        for (int attempt = 0; attempt <= MAX_OUTPUT_CORRECTIONS; attempt++) {
            long startMs = System.currentTimeMillis();
            ChatResponse response = bundle.chat().chatModel().chat(
                    ChatRequest.builder().messages(messages).build());
            long durationMs = System.currentTimeMillis() - startMs;
            llmCallTracker.record(process, response, durationMs, modelAlias);

            AiMessage reply = response.aiMessage();
            String text = reply == null ? "" : reply.text();
            if (text == null) text = "";

            if (state.isAuditLlmCalls()) {
                appendLlmRecord(state, systemPrompt, text, modelAlias, durationMs, attempt);
            }

            try {
                parsed = parseAndValidate(text);
                validationError = null;
                break;
            } catch (ProposeValidationException ve) {
                validationError = ve.getMessage();
                log.info("Slartibartfast id='{}' PROPOSING attempt {} validation failed: {}",
                        process.getId(), attempt, validationError);
                if (attempt < MAX_OUTPUT_CORRECTIONS) {
                    messages.add(AiMessage.from(text));
                    messages.add(UserMessage.from(
                            "Your last JSON was rejected: "
                                    + validationError
                                    + "\n\nCorrect it and emit a single JSON "
                                    + "object matching the schema."));
                }
            }
        }

        if (parsed == null) {
            state.setFailureReason("PROPOSING failed after "
                    + MAX_OUTPUT_CORRECTIONS + " corrections — last error: "
                    + validationError);
            appendIteration(state,
                    summariseInputs(state),
                    "FAILED — " + validationError,
                    PhaseIteration.IterationOutcome.FAILED,
                    recoveryHint != null ? "recovery" : "initial",
                    latestLlmRecordId(state));
            return;
        }

        applyToState(state, parsed);

        boolean wasRecovery = state.getPendingRecovery() != null
                && state.getPendingRecovery().getToPhase() == ArchitectStatus.PROPOSING;
        if (wasRecovery) {
            state.setPendingRecovery(null);
        }

        appendIteration(state,
                summariseInputs(state),
                "recipe '" + parsed.name + "' (" + parsed.yaml.length()
                        + " chars yaml, conf=" + parsed.confidence + ")",
                PhaseIteration.IterationOutcome.PASSED,
                wasRecovery ? "recovery" : "initial",
                latestLlmRecordId(state));
    }

    // ──────────────────── Prompt building ────────────────────

    private static String buildInitialUserPrompt(
            ArchitectState state,
            @Nullable String recoveryHint,
            List<ResolvedRecipe> availableRecipes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Output schema type: ")
                .append(state.getOutputSchemaType()).append("\n\n");

        sb.append("Framed goal:\n").append(state.getGoal().getFramed())
                .append("\n\n");

        sb.append("acceptanceCriteria:\n");
        for (Criterion c : state.getAcceptanceCriteria()) {
            sb.append("  ").append(c.getId()).append(": ")
                    .append(c.getText()).append("\n");
        }
        sb.append("\n");

        sb.append("subgoals (every plan decision you tie to one MUST "
                + "cite its sg-id in justifications):\n");
        for (Subgoal sg : state.getSubgoals()) {
            sb.append("  ").append(sg.getId());
            if (sg.isSpeculative()) sb.append(" [SPECULATIVE]");
            sb.append(": ").append(sg.getGoal()).append("\n");
        }
        sb.append("\n");

        // MARVIN_RECIPE only: project-available sub-recipes that the
        // generated marvin-recipe can list in allowedSubTaskRecipes.
        // Without this the LLM tends to leave the constraint empty
        // and the runtime PLAN takes shortcuts.
        if (state.getOutputSchemaType() == OutputSchemaType.MARVIN_RECIPE) {
            sb.append("Available sub-recipes in the project (excluding "
                    + "your own _slart/* generated bucket):\n");
            if (availableRecipes.isEmpty()) {
                sb.append("  (none)\n\n")
                        .append("Because there are NO project sub-recipes:\n")
                        .append("  - DO NOT set params.allowedSubTaskRecipes "
                                + "(omit the field entirely).\n")
                        .append("  - DO NOT set params.recipesOnlyViaExpand.\n")
                        .append("  - Inventing recipe names ('web-research', "
                                + "'analyze', 'marvin-worker', etc.) will be "
                                + "rejected by the validator — those names "
                                + "do not resolve at runtime.\n")
                        .append("  - Drive the plan via the promptPrefix "
                                + "alone; let Marvin's PLAN-LLM pick task "
                                + "kinds (WORKER without recipe = generic "
                                + "ford worker, EXPAND_FROM_DOC, etc.) at "
                                + "runtime.\n\n");
            } else {
                for (ResolvedRecipe r : availableRecipes) {
                    sb.append("  - ").append(r.name())
                            .append(" [engine=").append(r.engine())
                            .append("]: ")
                            .append(abbrev(r.description(), 100))
                            .append("\n");
                }
                sb.append("\nIf your subgoals map to any of these recipes, "
                        + "set allowedSubTaskRecipes to the matching subset "
                        + "and reference each recipe in the promptPrefix as "
                        + "`taskSpec.recipe`. Remember the KIND-block parity "
                        + "rule: the number of KIND blocks MUST equal the "
                        + "size of allowedSubTaskRecipes. Use ONLY the names "
                        + "listed above — every name must resolve to a real "
                        + "project recipe.\n\n");
            }
        }

        if (recoveryHint != null && !recoveryHint.isBlank()) {
            sb.append("IMPORTANT — the previous proposing attempt was "
                    + "rejected. Correction hint:\n")
                    .append(recoveryHint).append("\n\n");
        }

        sb.append("Now emit a single JSON object matching the schema. "
                + "The `yaml` field contains the full recipe YAML; "
                + "`justifications` maps every constraint-key to an "
                + "sg-id; `shapeRationale` explains the plan shape in "
                + "1-2 sentences.");
        return sb.toString();
    }

    // ──────────────────── Parse + light validate ────────────────────

    @SuppressWarnings("unchecked")
    private ProposeResult parseAndValidate(String text) {
        String jsonOnly = extractJsonObject(text);
        if (jsonOnly == null) {
            throw new ProposeValidationException(
                    "no JSON object found in reply");
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(jsonOnly, Map.class);
        } catch (RuntimeException e) {
            throw new ProposeValidationException(
                    "JSON parse error: " + e.getMessage());
        }

        Object n = root.get("name");
        if (!(n instanceof String name) || name.isBlank()) {
            throw new ProposeValidationException(
                    "required field 'name' missing or blank");
        }

        Object y = root.get("yaml");
        if (!(y instanceof String yaml) || yaml.isBlank()) {
            throw new ProposeValidationException(
                    "required field 'yaml' missing or blank");
        }

        Object j = root.get("justifications");
        if (!(j instanceof Map<?, ?> jMap)) {
            throw new ProposeValidationException(
                    "required field 'justifications' missing or not an object");
        }
        Map<String, String> justifications = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : jMap.entrySet()) {
            if (!(e.getKey() instanceof String key)
                    || !(e.getValue() instanceof String val)) {
                throw new ProposeValidationException(
                        "justifications must be String→String "
                                + "(key=" + e.getKey() + ", value=" + e.getValue() + ")");
            }
            if (key.isBlank() || val.isBlank()) {
                throw new ProposeValidationException(
                        "justifications entry with blank key/value: '"
                                + key + "' → '" + val + "'");
            }
            justifications.put(key, val);
        }
        if (justifications.isEmpty()) {
            throw new ProposeValidationException(
                    "justifications must not be empty — every "
                            + "constraint-key MUST point to an sg-id");
        }

        double confidence = 0.5;
        Object c = root.get("confidence");
        if (c instanceof Number num) {
            confidence = num.doubleValue();
            if (confidence < 0.0 || confidence > 1.0) {
                throw new ProposeValidationException(
                        "confidence " + confidence + " outside 0.0..1.0");
            }
        }

        Object sr = root.get("shapeRationale");
        if (!(sr instanceof String shapeRationale) || shapeRationale.isBlank()) {
            throw new ProposeValidationException(
                    "required field 'shapeRationale' missing or blank");
        }

        return new ProposeResult(
                name.trim(), yaml, justifications, confidence,
                shapeRationale.trim());
    }

    // ──────────────────── State application ────────────────────

    private void applyToState(ArchitectState state, ProposeResult parsed) {
        // Replace any previous PROPOSING-tier rationale.
        List<Rationale> pool = new ArrayList<>();
        for (Rationale r : state.getRationales()) {
            if (r.getInferredAt() != ArchitectStatus.PROPOSING) {
                pool.add(r);
            }
        }
        String shapeRationaleId = "rt" + (pool.size() + 1);
        pool.add(Rationale.builder()
                .id(shapeRationaleId)
                .text(parsed.shapeRationale)
                .inferredAt(ArchitectStatus.PROPOSING)
                .build());
        state.setRationales(pool);

        state.setProposedRecipe(RecipeDraft.builder()
                .name(parsed.name)
                .outputSchemaType(state.getOutputSchemaType())
                .yaml(parsed.yaml)
                .justifications(parsed.justifications)
                .confidence(parsed.confidence)
                .shapeRationaleId(shapeRationaleId)
                .build());
    }

    // ──────────────────── Audit append ────────────────────

    private static void appendLlmRecord(
            ArchitectState state,
            String systemPrompt,
            String response,
            String modelAlias,
            long durationMs,
            int attempt) {
        List<LlmCallRecord> records = new ArrayList<>(state.getLlmCallRecords());
        String id = "llm" + (records.size() + 1);
        records.add(LlmCallRecord.builder()
                .id(id)
                .phase(ArchitectStatus.PROPOSING)
                .iteration(attempt + 1)
                .promptHash(sha256Hex(systemPrompt + "\n----\n" + state.getRunId()))
                .promptPreview(abbrev(systemPrompt, PROMPT_PREVIEW_LIMIT))
                .response(response)
                .modelAlias(modelAlias)
                .durationMs(durationMs)
                .build());
        state.setLlmCallRecords(records);
    }

    private static @Nullable String latestLlmRecordId(ArchitectState state) {
        List<LlmCallRecord> records = state.getLlmCallRecords();
        if (records.isEmpty()) return null;
        return records.get(records.size() - 1).getId();
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome,
            String triggeredBy,
            @Nullable String llmRecordId) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.PROPOSING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.PROPOSING)
                .triggeredBy(triggeredBy)
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .llmCallRecordId(llmRecordId)
                .build());
        state.setIterations(log);
    }

    // ──────────────────── Helpers ────────────────────

    /**
     * Lists every recipe currently visible to the project's
     * recipe-cascade, minus the {@code _slart/} bucket itself —
     * Slartibartfast's own outputs are not eligible as
     * sub-recipes for the recipe being generated.
     *
     * <p>The list goes verbatim into the user-prompt so the LLM
     * has concrete recipe names to put into
     * {@code allowedSubTaskRecipes}. Without this the LLM tends to
     * either invent names or omit the constraint entirely; the
     * latter empirically results in Marvin's runtime PLAN-LLM
     * taking shortcuts and the pipeline not running.
     */
    private List<ResolvedRecipe> listAvailableSubRecipes(
            ThinkProcessDocument process) {
        try {
            return recipeLoader.listAll(
                            process.getTenantId(), process.getProjectId())
                    .stream()
                    .filter(r -> !r.name().startsWith("_slart/"))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Slartibartfast id='{}' PROPOSING failed listing project "
                            + "recipes: {} — proceeding without recipe-list",
                    process.getId(), e.toString());
            return List.of();
        }
    }

    private static String summariseInputs(ArchitectState state) {
        return "subgoals=" + state.getSubgoals().size()
                + ", criteria=" + state.getAcceptanceCriteria().size()
                + ", schemaType=" + state.getOutputSchemaType();
    }

    private static @Nullable String extractJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String abbrev(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ──────────────────── Internal types ────────────────────

    private record ProposeResult(
            String name,
            String yaml,
            Map<String, String> justifications,
            double confidence,
            String shapeRationale) {}

    private static class ProposeValidationException extends RuntimeException {
        ProposeValidationException(String message) { super(message); }
    }
}
