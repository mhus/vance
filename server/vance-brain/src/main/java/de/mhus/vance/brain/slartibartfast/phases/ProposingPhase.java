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
import de.mhus.vance.brain.slartibartfast.architect.SchemaArchitect;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * PROPOSING phase — turns the bound subgoals + framed goal into a
 * concrete recipe YAML. Schema-specific knowledge (which
 * system-prompt to feed the LLM, whether to list sub-recipes in
 * the user prompt, which {@code engine:} value to emit) lives in
 * a {@link SchemaArchitect} bean keyed by
 * {@link OutputSchemaType}. This phase is the schema-agnostic
 * orchestrator: build messages → call LLM → parse → light
 * shape-check → return RecipeDraft.
 *
 * <p>Justifications: every recipe-level constraint or phase
 * decision must reference the {@link Subgoal#getId()} that
 * motivates it — the audit trail VALIDATING uses to reject
 * references to ghost ids.
 */
@Component
@Slf4j
public class ProposingPhase {

    private static final String ENGINE_NAME = "slartibartfast";
    private static final int MAX_OUTPUT_CORRECTIONS = 2;
    private static final int PROMPT_PREVIEW_LIMIT = 500;


    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final ObjectMapper objectMapper;
    private final RecipeLoader recipeLoader;
    private final de.mhus.vance.brain.context.LanguageContextResolver languageContextResolver;
    /** Resolved at construction from the {@link SchemaArchitect}
     *  beans Spring discovered. One entry per
     *  {@link OutputSchemaType}; missing entries cause a clear
     *  failure at PROPOSING time rather than a silent fallback. */
    private final Map<OutputSchemaType, SchemaArchitect> architects;

    public ProposingPhase(
            EngineChatFactory engineChatFactory,
            LlmCallTracker llmCallTracker,
            ObjectMapper objectMapper,
            RecipeLoader recipeLoader,
            de.mhus.vance.brain.context.LanguageContextResolver languageContextResolver,
            List<SchemaArchitect> schemaArchitects) {
        this.engineChatFactory = engineChatFactory;
        this.llmCallTracker = llmCallTracker;
        this.objectMapper = objectMapper;
        this.recipeLoader = recipeLoader;
        this.languageContextResolver = languageContextResolver;
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

        SchemaArchitect architect = architects.get(state.getOutputSchemaType());
        if (architect == null) {
            throw new IllegalStateException(
                    "No SchemaArchitect bean registered for "
                            + state.getOutputSchemaType()
                            + " — every OutputSchemaType requires a bean");
        }
        String systemPrompt = architect.proposingSystemPrompt();

        // Kit-provided guidance hook — recipes (writing-kit, school-
        // essay-kit, …) can inject recipe-shape conventions via the
        // engineParams.proposingHints param. Slart's bundled prompt
        // stays generic; the kit owns its task-specific additions
        // (typical example: "persist artifacts via doc_create"
        // patterns). Appended verbatim under a labelled header so
        // the LLM can distinguish engine-defined contract from
        // kit-supplied extension.
        String kitHints = state.getProposingHints();
        if (kitHints != null && !kitHints.isBlank()) {
            systemPrompt = systemPrompt
                    + "\n\n=== Kit-provided guidance for this PROPOSING run ===\n"
                    + kitHints.trim()
                    + "\n=== End kit-provided guidance ===\n";
        }

        // Schemas that reference project sub-recipes (Marvin's
        // allowedSubTaskRecipes, Zaphod's heads.recipe) get the
        // project's recipe inventory injected into the user prompt
        // so the LLM picks real names instead of inventing
        // plausible-sounding ones.
        List<ResolvedRecipe> availableRecipes = architect.wantsSubRecipeListing()
                ? listAvailableSubRecipes(process)
                : List.of();

        List<ChatMessage> messages = new ArrayList<>();
        String langBlock = languageContextResolver.formatBlock(process);
        messages.add(SystemMessage.from(langBlock.isEmpty()
                ? systemPrompt
                : systemPrompt + "\n\n" + langBlock));
        messages.add(UserMessage.from(
                buildInitialUserPrompt(state, recoveryHint,
                        architect, availableRecipes)));

        ProposeResult parsed = null;
        String validationError = null;
        for (int attempt = 0; attempt <= MAX_OUTPUT_CORRECTIONS; attempt++) {
            long startMs = System.currentTimeMillis();
            ChatRequest request = ChatRequest.builder().messages(messages).build();
            ChatResponse response = bundle.chat().chatModel().chat(request);
            long durationMs = System.currentTimeMillis() - startMs;
            llmCallTracker.record(process, request, response, durationMs, modelAlias);

            AiMessage reply = response.aiMessage();
            String text = reply == null ? "" : reply.text();
            if (text == null) text = "";

            if (state.isAuditLlmCalls()) {
                appendLlmRecord(state, systemPrompt, text, modelAlias, durationMs, attempt);
            }

            try {
                parsed = parseAndValidate(text, architect);
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
            SchemaArchitect architect,
            List<ResolvedRecipe> availableRecipes) {
        StringBuilder sb = new StringBuilder();

        // Recovery hint goes FIRST and loud — easy to miss when
        // buried at the end of a long prompt. Critically, we
        // include the PREVIOUSLY REJECTED yaml verbatim and ask
        // for a targeted revision, not a full re-generation. The
        // LLM is much better at fixing N specific points in an
        // existing structure than at re-imagining the whole
        // recipe each time.
        if (recoveryHint != null && !recoveryHint.isBlank()) {
            sb.append("================================================\n");
            sb.append("⚠  CRITICAL — PREVIOUS ATTEMPT WAS REJECTED ⚠\n");
            sb.append("================================================\n\n");

            // The previous rejected yaml — non-null after at least
            // one PROPOSING pass.
            de.mhus.vance.api.slartibartfast.RecipeDraft prev =
                    state.getProposedRecipe();
            if (prev != null && prev.getYaml() != null
                    && !prev.getYaml().isBlank()) {
                sb.append("HERE IS THE RECIPE YOU PROPOSED LAST TIME "
                        + "(do NOT regenerate from scratch — REVISE "
                        + "this structure to fix the errors listed "
                        + "below, keep everything else as-is):\n\n");
                sb.append("```yaml\n").append(prev.getYaml()).append("\n```\n\n");
            }

            sb.append("ERRORS FOUND IN THE ABOVE RECIPE — fix EVERY "
                    + "one of them, then re-emit the COMPLETE recipe "
                    + "(modified yaml + matching justifications +  "
                    + "updated shapeRationale that mentions the "
                    + "revision):\n\n");
            sb.append(recoveryHint).append("\n");
            sb.append("================================================\n\n");
        }

        sb.append("Output schema type: ")
                .append(state.getOutputSchemaType()).append("\n\n");

        // Edit-mode: the LLM patches an existing recipe instead of
        // inventing one. Show the original yaml verbatim plus the
        // modification request, and instruct the LLM to keep
        // everything except the parts the modification touches.
        if (state.getMode()
                == de.mhus.vance.api.slartibartfast.ArchitectMode.EDIT
                && state.getExistingRecipeYaml() != null) {
            sb.append("================================================\n");
            sb.append("⚙  EDIT MODE — PATCH THE EXISTING RECIPE ⚙\n");
            sb.append("================================================\n\n");
            sb.append("You are NOT authoring a new recipe from scratch. "
                    + "You are modifying THIS existing recipe (named '")
                    .append(state.getTargetRecipeName())
                    .append("'):\n\n");
            sb.append("```yaml\n").append(state.getExistingRecipeYaml())
                    .append("\n```\n\n");
            sb.append("Modification requested:\n  ")
                    .append(state.getModificationSummary() == null
                            ? "(no summary — infer from the framed goal below)"
                            : state.getModificationSummary())
                    .append("\n\n");
            sb.append("RULES:\n");
            sb.append("- Keep ALL existing structures EXCEPT what the "
                    + "modification explicitly changes.\n");
            sb.append("- Preserve existing names, personae, persona texts, "
                    + "phase names, head recipe references — anything not "
                    + "named in the modification stays IDENTICAL.\n");
            sb.append("- Emit the COMPLETE modified recipe yaml (NOT a "
                    + "diff). The validator parses the full yaml.\n");
            sb.append("- justifications: for unchanged constraint-keys, "
                    + "you may reuse the original justifications. For "
                    + "changed/new constraint-keys, point at the sg-ids "
                    + "from the modification's framed goal below.\n");
            sb.append("================================================\n\n");
        }

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

        // Schema-specific context block (sub-recipe inventory, shape
        // hints) is owned by the architect bean. Vogon contributes
        // nothing here; Marvin lists allowedSubTaskRecipes candidates;
        // Zaphod lists head-recipe candidates.
        architect.appendProposingContext(sb, state, availableRecipes);

        sb.append("Now emit a single JSON object matching the schema. "
                + "The `yaml` field contains the full recipe YAML; "
                + "`justifications` maps every constraint-key to an "
                + "sg-id; `shapeRationale` explains the plan shape in "
                + "1-2 sentences.");
        return sb.toString();
    }

    // ──────────────────── Parse + light validate ────────────────────

    @SuppressWarnings("unchecked")
    private ProposeResult parseAndValidate(
            String text, SchemaArchitect architect) {
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

        // Delegate name + YAML extraction to the architect. Vogon /
        // Zaphod read both off the JSON root; Marvin reads name from
        // root.params.name and renders the YAML from a bundled
        // template (see MarvinArchitect).
        String name;
        try {
            name = architect.extractRecipeName(root);
        } catch (RuntimeException e) {
            throw new ProposeValidationException(e.getMessage());
        }

        String yaml;
        try {
            yaml = architect.extractRecipeYaml(root);
        } catch (RuntimeException e) {
            throw new ProposeValidationException(e.getMessage());
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
