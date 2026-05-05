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
            Du bist der PROPOSING-Knoten der Slartibartfast-Engine.
            Aus dem framed Goal und den Subgoals erzeugst du ein
            Marvin-Recipe — eine PLAN-getriebene Decomposition mit
            Constraints, die Marvins PLAN-Validator zur Laufzeit
            durchsetzt.

            HARTER OUTPUT-VERTRAG:
            - Beende deinen Reply mit GENAU einem JSON-Objekt.
            - KEIN Markdown-Codeblock (kein ```json … ```).
            - KEIN Text vor oder nach dem JSON.

            Schema:
                {
                  "name":           "<recipe-name, kebab-case>",
                  "yaml":           "<full recipe YAML, see structure below>",
                  "justifications": {
                    "params.maxPlanCorrections": "<sg-id>",
                    "promptPrefix":              "<sg-id>",
                    ...
                  },
                  "confidence":     <0.0..1.0>,
                  "shapeRationale": "<why this shape — 1-2 Sätze>"
                }

            YAML-Struktur (Pflicht):
                name: <name, gleich wie oben>
                description: |
                  <1-2 Sätze>
                engine: marvin
                params:
                  rootTaskKind: PLAN
                  maxPlanCorrections: 2
                  # Optional weitere constraints (siehe unten)
                promptPrefix: |
                  Du bist der `<name>`-PLAN-Knoten. Du erzeugst
                  GENAU N Children in dieser Reihenfolge:

                  KIND 1 — <description matching subgoal sg1>
                  KIND 2 — <description matching sg2>
                  ...

                  Jeder Child-Knoten als JSON gemäß Marvin-Schema:
                  {"taskKind": "WORKER" | "EXPAND_FROM_DOC" | "USER_INPUT",
                   "goal": "...",
                   "taskSpec": { ... }}

                  Output-Vertrag, nur diese N Children:
                      {"children": [<KIND 1>, <KIND 2>, ...]}

            Optionale Marvin-Constraints (in `params:`):
            - allowedSubTaskRecipes: [list]    # whitelist child recipes
            - recipesOnlyViaExpand: [list]     # subset that must be EXPAND_FROM_DOC childTemplate
            - allowedExpandDocumentRefPaths: [paths]  # whitelist EXPAND.documentRef.path
            - requiredChildTemplateRecipeParams: { recipe: [keys] }
            - disallowedTaskKinds: [AGGREGATE]
            - defaultExecutionMode: SEQUENTIAL | PARALLEL
            Setze diese NUR wenn die Subgoals sie tatsächlich
            motivieren — leerer params-Block ist OK für simple
            Pläne.

            promptPrefix:
            - Multi-Zeilen-Anweisung an den PLAN-LLM, der zur
              Laufzeit die konkreten Children erzeugt.
            - Pro Subgoal in der subgoals-Liste EIN KIND-Block.
            - Speziell wenn ein Subgoal "pro Item iterieren" sagt
              (z.B. "schreibe pro Kapitel ein Kapitel"), nutze
              EXPAND_FROM_DOC mit documentRef + childTemplate
              statt manueller Aufzählung.

            justifications-Map: jeder gesetzte constraint-key
            (params.X oder promptPrefix) MUSS auf einen sg-id
            zeigen, der existiert.

            Wenn du diesen Vertrag verletzt, lehnt der Validator
            deinen Output ab und du wirst um Korrektur gebeten.
            """;

    private static final String SYSTEM_PROMPT_VOGON = """
            Du bist der PROPOSING-Knoten der Slartibartfast-Engine.
            Aus dem framed Goal und den Subgoals erzeugst du ein
            ausführbares Recipe für die Vogon-Engine. Das Recipe
            wickelt eine inline strategyPlanYaml (Vogon-Strategy)
            ein.

            HARTER OUTPUT-VERTRAG:
            - Beende deinen Reply mit GENAU einem JSON-Objekt.
            - KEIN Markdown-Codeblock (kein ```json … ```).
            - KEIN Text vor oder nach dem JSON.

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
                  "shapeRationale": "<why this shape — 1-2 Sätze>"
                }

            YAML-Struktur (Pflicht):
                name: <name, gleich wie oben>
                description: |
                  <1-2 Sätze>
                engine: vogon
                params:
                  strategyPlanYaml: |
                    name: <strategy-name>
                    version: "1"
                    phases:
                      - name: <phase-name>
                        worker: <recipe-name oder ford>
                        workerInput: |
                          <prompt for the worker>
                        gate: { requires: [<phase-name>_completed] }

            justifications-Map (Pflicht):
            - JEDER constraint-key, den du im YAML setzt, MUSS hier
              auf einen sg-id zeigen, der in subgoals existiert.
            - Konvention für constraint-keys:
              - "name" für den Recipe-Namen
              - "phases.<idx>.worker" für jede Phase
              - "engine" wenn du etwas anderes als "vogon" wählst
                (sollte hier nie passieren — Output-Schema-Typ ist
                VOGON_STRATEGY)

            confidence:
            - 1.0 - speculative-Anteil = grobe Heuristik
            - VALIDATING wird das prüfen.

            shapeRationale: WARUM gerade diese Anzahl von Phasen
            in dieser Reihenfolge. Bezieht sich auf die Plan-Form,
            nicht einzelne Phasen.

            Wenn du diesen Vertrag verletzt, lehnt der Validator
            deinen Output ab und du wirst um Korrektur gebeten.
            """;

    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final ObjectMapper objectMapper;

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

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(buildInitialUserPrompt(state, recoveryHint)));

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
                            "Dein letztes JSON wurde abgelehnt: "
                                    + validationError
                                    + "\n\nKorrigiere und liefere erneut ein "
                                    + "einzelnes JSON-Objekt nach Schema."));
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
            ArchitectState state, @Nullable String recoveryHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("Output-Schema-Typ: ")
                .append(state.getOutputSchemaType()).append("\n\n");

        sb.append("Framed Goal:\n").append(state.getGoal().getFramed())
                .append("\n\n");

        sb.append("acceptanceCriteria:\n");
        for (Criterion c : state.getAcceptanceCriteria()) {
            sb.append("  ").append(c.getId()).append(": ")
                    .append(c.getText()).append("\n");
        }
        sb.append("\n");

        sb.append("subgoals (jeder muss in justifications zitiert werden, "
                + "wenn du eine Plan-Entscheidung an ihn knüpfst):\n");
        for (Subgoal sg : state.getSubgoals()) {
            sb.append("  ").append(sg.getId());
            if (sg.isSpeculative()) sb.append(" [SPECULATIVE]");
            sb.append(": ").append(sg.getGoal()).append("\n");
        }
        sb.append("\n");

        if (recoveryHint != null && !recoveryHint.isBlank()) {
            sb.append("WICHTIG — der vorige Proposing-Versuch wurde "
                    + "abgelehnt. Korrektur-Hinweis:\n")
                    .append(recoveryHint).append("\n\n");
        }

        sb.append("Liefere JETZT ein einzelnes JSON-Objekt nach Schema. "
                + "Das `yaml`-Feld enthält das komplette Recipe-YAML; "
                + "`justifications` mappt jeden constraint-key auf einen "
                + "sg-id; `shapeRationale` erklärt die Plan-Form in "
                + "1-2 Sätzen.");
        return sb.toString();
    }

    // ──────────────────── Parse + light validate ────────────────────

    @SuppressWarnings("unchecked")
    private ProposeResult parseAndValidate(String text) {
        String jsonOnly = extractJsonObject(text);
        if (jsonOnly == null) {
            throw new ProposeValidationException(
                    "kein JSON-Objekt im Reply gefunden");
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(jsonOnly, Map.class);
        } catch (RuntimeException e) {
            throw new ProposeValidationException(
                    "JSON-Parse-Fehler: " + e.getMessage());
        }

        Object n = root.get("name");
        if (!(n instanceof String name) || name.isBlank()) {
            throw new ProposeValidationException(
                    "Pflichtfeld 'name' fehlt oder ist leer");
        }

        Object y = root.get("yaml");
        if (!(y instanceof String yaml) || yaml.isBlank()) {
            throw new ProposeValidationException(
                    "Pflichtfeld 'yaml' fehlt oder ist leer");
        }

        Object j = root.get("justifications");
        if (!(j instanceof Map<?, ?> jMap)) {
            throw new ProposeValidationException(
                    "Pflichtfeld 'justifications' fehlt oder ist kein Object");
        }
        Map<String, String> justifications = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : jMap.entrySet()) {
            if (!(e.getKey() instanceof String key)
                    || !(e.getValue() instanceof String val)) {
                throw new ProposeValidationException(
                        "justifications muss String→String sein "
                                + "(key=" + e.getKey() + ", value=" + e.getValue() + ")");
            }
            if (key.isBlank() || val.isBlank()) {
                throw new ProposeValidationException(
                        "justifications-Eintrag mit leerem key/value: '"
                                + key + "' → '" + val + "'");
            }
            justifications.put(key, val);
        }
        if (justifications.isEmpty()) {
            throw new ProposeValidationException(
                    "justifications darf nicht leer sein — jeder "
                            + "constraint-key MUSS auf einen sg-id zeigen");
        }

        double confidence = 0.5;
        Object c = root.get("confidence");
        if (c instanceof Number num) {
            confidence = num.doubleValue();
            if (confidence < 0.0 || confidence > 1.0) {
                throw new ProposeValidationException(
                        "confidence " + confidence + " außerhalb 0.0..1.0");
            }
        }

        Object sr = root.get("shapeRationale");
        if (!(sr instanceof String shapeRationale) || shapeRationale.isBlank()) {
            throw new ProposeValidationException(
                    "Pflichtfeld 'shapeRationale' fehlt oder ist leer");
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
