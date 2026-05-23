package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.ExecutionDecision;
import de.mhus.vance.api.slartibartfast.LlmCallRecord;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * EXECUTION_PLANNING phase — Slart's decision-LLM-call on
 * whether to execute the freshly persisted recipe, and with what
 * prompt. Sits between PERSISTING and EXECUTING. Output is one
 * of three {@link ExecutionDecision} values; the engine
 * dispatches accordingly.
 *
 * <p>Design rationale: a plain auto-execute would (a) be wrong
 * for architecture-recipes (Zaphod, Project-Setup) that are
 * reusable assets without a single execution mission, and (b)
 * risk firing off a Slart-LLM-invented mission with real
 * side-effects when the user only wanted to author the recipe.
 * The decision-phase makes the choice explicit, audited, and
 * driven by signals in the user's own description.
 *
 * <p>Decision tree (encoded in the system prompt):
 * <ul>
 *   <li>Explicit test prompt in user description ("frage 'X'",
 *       "teste mit 'X'", "und versuch's mit 'X'") →
 *       {@link ExecutionDecision#USE_USER_PROMPT} with the
 *       extracted prompt.</li>
 *   <li>Explicit no-test ("nur anlegen", "nicht ausführen",
 *       "kein Test") → {@link ExecutionDecision#SKIP}.</li>
 *   <li>Pipeline schema (VOGON / MARVIN) with concrete mission
 *       in the description → USE_USER_PROMPT with the whole
 *       description as goal (the description IS the work).</li>
 *   <li>Architecture schema (ZAPHOD, later PROJECT_SETUP)
 *       without explicit test signal → SKIP (the recipe is a
 *       reusable asset; execution comes later with real
 *       questions).</li>
 *   <li>Architecture schema with "und teste das mal" but no
 *       concrete test prompt → {@link ExecutionDecision#USE_GENERATED_PROMPT}
 *       with a simple LLM-invented test question.</li>
 * </ul>
 *
 * <p>See {@code planning/slart-as-project-architect.md} §D-3.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionPlanningPhase {

    private static final String ENGINE_NAME = "slartibartfast";

    private static final int MAX_OUTPUT_CORRECTIONS = 2;

    private static final int PROMPT_PREVIEW_LIMIT = 500;

    private static final String SYSTEM_PROMPT = """
            You are the EXECUTION_PLANNING node of the Slartibartfast
            engine. The recipe has just been persisted. Your job:
            decide whether to execute it, and with what prompt.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO text before or after the JSON.

            Schema (every field mandatory):
                {
                  "decision": "USE_USER_PROMPT" | "USE_GENERATED_PROMPT" | "SKIP",
                  "prompt":   "<string when decision != SKIP, else null>",
                  "reason":   "<1-2 sentences why>"
                }

            Decision rules (apply IN ORDER, first match wins):

            1. Explicit no-test in user description.
               Signals: "nur anlegen", "nicht ausführen", "kein
               Test", "do not run", "just save", "skip execution".
               → decision = "SKIP", prompt = null.

            2. Explicit test prompt in user description.
               Signals: "und frage 'X'", "teste mit 'X'", "und
               versuch's mit 'X'", "ask it 'X'", "test it with 'X'".
               Extract the literal X (preserve case, punctuation).
               → decision = "USE_USER_PROMPT", prompt = X.

            3. User description IS a concrete mission AND schema
               is pipeline (VOGON_STRATEGY / MARVIN_RECIPE).
               Pipeline recipes are one-shot — the recipe IS the
               mission. → decision = "USE_USER_PROMPT", prompt =
               the original user description (verbatim).

            4. User description IS a concrete mission AND schema
               is architecture (ZAPHOD_RECIPE).
               The user asked Slart to BUILD the architecture and
               implicitly expects something to come out of it on
               this run too. → decision = "USE_USER_PROMPT",
               prompt = original user description.

            5. Architecture schema (ZAPHOD_RECIPE) AND user
               description is purely structural ("Erstelle ein
               Gremium aus …", "Build a council of …") with NO
               concrete question.
               The recipe is a reusable asset — execution comes
               later with real questions. → decision = "SKIP".

            6. Architecture schema AND user said "teste mal" /
               "und probier's aus" / "smoke test it" but gave no
               specific test prompt.
               → decision = "USE_GENERATED_PROMPT", prompt = a
               SIMPLE one-sentence test question relevant to the
               recipe's domain (e.g. for a refactor council:
               "Should we adopt React Server Components for our
               app?"; for a science panel: "What is the dominant
               extinction theory for the dinosaurs?"). Keep it
               trivial — this is verification, not the real use.

            Be conservative — when in doubt between SKIP and
            USE_GENERATED_PROMPT, prefer SKIP. The user can always
            run the recipe later with a real question.

            reason: a short justification naming the rule that
            applied. Surfaces in audit + DONE-payload.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final ObjectMapper objectMapper;

    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, ENGINE_NAME);
        String modelAlias = bundle.primaryConfig().provider() + ":"
                + bundle.primaryConfig().modelName();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildUserPrompt(state)));

        DecisionResult parsed = null;
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
                appendLlmRecord(state, text, modelAlias, durationMs, attempt);
            }

            try {
                parsed = parseAndValidate(text);
                validationError = null;
                break;
            } catch (DecisionValidationException ve) {
                validationError = ve.getMessage();
                log.info("Slartibartfast id='{}' EXECUTION_PLANNING attempt {} "
                                + "validation failed: {}",
                        process.getId(), attempt, validationError);
                if (attempt < MAX_OUTPUT_CORRECTIONS) {
                    messages.add(AiMessage.from(text));
                    messages.add(UserMessage.from(
                            "Your last JSON was rejected: " + validationError
                                    + "\n\nCorrect it and emit a single JSON "
                                    + "object matching the schema above."));
                }
            }
        }

        if (parsed == null) {
            // Conservative fallback: SKIP on validation failure. Better
            // to not run than to run with an undefined prompt.
            log.warn("Slartibartfast id='{}' EXECUTION_PLANNING budget "
                            + "exhausted — defaulting to SKIP "
                            + "(last error: {})",
                    process.getId(), validationError);
            state.setExecutionDecision(ExecutionDecision.SKIP);
            state.setExecutionPrompt(null);
            state.setExecutionDecisionReason(
                    "Decision-LLM produced no valid output after "
                            + MAX_OUTPUT_CORRECTIONS + " attempts — "
                            + "defaulting to SKIP for safety. Last error: "
                            + validationError);
            appendIteration(state,
                    "decision-LLM",
                    "FALLBACK_SKIP — " + validationError,
                    PhaseIteration.IterationOutcome.PASSED,
                    latestLlmRecordId(state));
            return;
        }

        state.setExecutionDecision(parsed.decision);
        state.setExecutionPrompt(parsed.prompt);
        state.setExecutionDecisionReason(parsed.reason);

        log.info("Slartibartfast id='{}' EXECUTION_PLANNING decided {} "
                        + "(prompt={} chars, reason='{}')",
                process.getId(), parsed.decision,
                parsed.prompt == null ? 0 : parsed.prompt.length(),
                parsed.reason);

        appendIteration(state,
                "schema=" + state.getOutputSchemaType()
                        + ", description=" + abbrev(state.getUserDescription(), 60),
                parsed.decision.name() + " — " + parsed.reason,
                PhaseIteration.IterationOutcome.PASSED,
                latestLlmRecordId(state));
    }

    // ──────────────────── Prompt building ────────────────────

    private static String buildUserPrompt(ArchitectState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Recipe schema type: ").append(state.getOutputSchemaType())
                .append("\n");
        sb.append("Persisted at: ").append(state.getPersistedRecipePath())
                .append("\n");
        if (state.getProposedRecipe() != null
                && state.getProposedRecipe().getName() != null) {
            sb.append("Recipe name: ").append(state.getProposedRecipe().getName())
                    .append("\n");
        }
        sb.append("\nOriginal user description (verbatim):\n")
                .append(state.getUserDescription()).append("\n\n");
        sb.append("Now emit a single JSON object matching the decision schema.");
        return sb.toString();
    }

    // ──────────────────── Parse + validate ────────────────────

    @SuppressWarnings("unchecked")
    private DecisionResult parseAndValidate(String text) {
        String jsonOnly = extractJsonObject(text);
        if (jsonOnly == null) {
            throw new DecisionValidationException("no JSON object found in reply");
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(jsonOnly, Map.class);
        } catch (RuntimeException e) {
            throw new DecisionValidationException(
                    "JSON parse error: " + e.getMessage());
        }

        Object decisionRaw = root.get("decision");
        if (!(decisionRaw instanceof String decisionStr) || decisionStr.isBlank()) {
            throw new DecisionValidationException(
                    "required field 'decision' missing or blank");
        }
        ExecutionDecision decision;
        try {
            decision = ExecutionDecision.valueOf(decisionStr.trim());
        } catch (IllegalArgumentException e) {
            throw new DecisionValidationException(
                    "decision '" + decisionStr + "' must be one of "
                            + "USE_USER_PROMPT / USE_GENERATED_PROMPT / SKIP");
        }

        Object promptRaw = root.get("prompt");
        String prompt;
        if (promptRaw == null || (promptRaw instanceof String s && s.isBlank())) {
            prompt = null;
        } else if (promptRaw instanceof String s) {
            prompt = s.trim();
        } else {
            throw new DecisionValidationException(
                    "field 'prompt' must be a string or null (got "
                            + promptRaw.getClass().getSimpleName() + ")");
        }

        if (decision == ExecutionDecision.SKIP && prompt != null) {
            // Tolerate but normalise — SKIP must not carry a prompt.
            prompt = null;
        }
        if (decision != ExecutionDecision.SKIP
                && (prompt == null || prompt.isBlank())) {
            throw new DecisionValidationException(
                    "decision=" + decision + " requires a non-blank prompt");
        }

        Object reasonRaw = root.get("reason");
        if (!(reasonRaw instanceof String reason) || reason.isBlank()) {
            throw new DecisionValidationException(
                    "required field 'reason' missing or blank");
        }

        return new DecisionResult(decision, prompt, reason.trim());
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

    // ──────────────────── Audit append ────────────────────

    private static void appendLlmRecord(
            ArchitectState state, String response, String modelAlias,
            long durationMs, int attempt) {
        List<LlmCallRecord> records = new ArrayList<>(state.getLlmCallRecords());
        String id = "llm" + (records.size() + 1);
        records.add(LlmCallRecord.builder()
                .id(id)
                .phase(ArchitectStatus.EXECUTION_PLANNING)
                .iteration(attempt + 1)
                .promptHash(sha256Hex(SYSTEM_PROMPT
                        + "\n----\n" + state.getUserDescription()))
                .promptPreview(abbrev(SYSTEM_PROMPT, PROMPT_PREVIEW_LIMIT))
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
            ArchitectState state, String input, String output,
            PhaseIteration.IterationOutcome outcome,
            @Nullable String llmRecordId) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.EXECUTION_PLANNING)
                .count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.EXECUTION_PLANNING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(input)
                .outputSummary(output)
                .outcome(outcome)
                .llmCallRecordId(llmRecordId)
                .build());
        state.setIterations(log);
    }

    // ──────────────────── Internal types ────────────────────

    private record DecisionResult(
            ExecutionDecision decision,
            @Nullable String prompt,
            String reason) {}

    private static class DecisionValidationException extends RuntimeException {
        DecisionValidationException(String message) { super(message); }
    }

    // ──────────────────── Utilities ────────────────────

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
}
