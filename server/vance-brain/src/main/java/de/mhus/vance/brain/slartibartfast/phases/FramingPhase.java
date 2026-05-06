package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.FramedGoal;
import de.mhus.vance.api.slartibartfast.LlmCallRecord;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Rationale;
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
 * FRAMING phase — turns the user's free-text request into a
 * structured {@link FramedGoal} with two distinct lists of
 * acceptance criteria: stated (verbatim from the user) and
 * assumed (inferred convention/domain/context, with confidence
 * and rationale).
 *
 * <p>Hard contract: LLM emits a single JSON object, no Markdown
 * wrap, no surrounding text. Validation failures trigger a
 * re-prompt with a concrete corrective hint, max
 * {@link #MAX_OUTPUT_CORRECTIONS} attempts. Past the budget, the
 * phase gives up and {@link ArchitectState#getFailureReason()} is
 * set — the caller transitions to
 * {@link ArchitectStatus#FAILED}.
 *
 * <p>Side effects on success:
 * <ul>
 *   <li>{@link ArchitectState#setGoal} populated</li>
 *   <li>One {@link Rationale} per assumed criterion appended</li>
 *   <li>One {@link LlmCallRecord} per attempt appended (when
 *       {@link ArchitectState#isAuditLlmCalls()})</li>
 *   <li>One {@link PhaseIteration} appended with link to the
 *       successful LLM call</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FramingPhase {

    private static final String ENGINE_NAME = "slartibartfast";

    private static final int MAX_OUTPUT_CORRECTIONS = 2;

    private static final int PROMPT_PREVIEW_LIMIT = 500;

    private static final String SYSTEM_PROMPT = """
            You are the FRAMING node of the Slartibartfast engine.
            Your only job: turn the user's free-text description
            into two lists of acceptance criteria — what was said
            verbatim, and what the user likely also expects without
            saying it.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO explanatory text BEFORE the JSON.
            - NO text AFTER the JSON.

            Schema (every field mandatory unless noted):
                {
                  "framed":          "<re-stated request, one sentence>",
                  "statedCriteria":  [
                    { "text": "<predicate>" }
                  ],
                  "assumedCriteria": [
                    {
                      "text":       "<predicate>",
                      "origin":     "INFERRED_CONVENTION" |
                                    "INFERRED_DOMAIN" |
                                    "INFERRED_CONTEXT",
                      "confidence": <0.0..1.0>,
                      "rationale":  "<why do you assume this?>"
                    }
                  ]
                }

            statedCriteria: only what is verbatim in the user text
            or an unambiguous paraphrase. Origin is implicitly
            USER_STATED; no confidence/rationale.

            assumedCriteria: what the user implies without saying it.
            - INFERRED_CONVENTION: generic convention (e.g. "write
              X" implies "save X as a file").
            - INFERRED_DOMAIN: domain-typical expectation (e.g.
              "chapters" implies "3-7 chapters").
            - INFERRED_CONTEXT: context inference (e.g. "user
              writes in German" implies "output in German").

            confidence scale (be conservative):
              0.95+ = "near-certain" (standard convention, clear text)
              0.70  = "likely"
              0.50  = "possible but not certain"
              0.30  = "guess"

            Prefer FEW well-grounded assumptions over many weakly
            grounded ones. If no assumptions are plausible, return
            `assumedCriteria: []`.

            Language: write the JSON content in English. The user-
            facing acceptance language follows the user's request
            and is not your concern in this phase.

            If you violate this contract the validator rejects your
            output and asks you to correct it. Better to emit a
            valid JSON on the first try.
            """;

    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final ObjectMapper objectMapper;

    /**
     * Runs the FRAMING LLM call (with retry-on-validation-fail) and
     * mutates {@code state} in place. Caller is responsible for
     * persisting the state and dispatching the next phase.
     */
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
        messages.add(UserMessage.from(buildInitialUserPrompt(state)));

        FramingResult parsed = null;
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
                appendLlmRecord(state, text, modelAlias, durationMs, attempt);
            }

            try {
                parsed = parseAndValidate(text);
                validationError = null;
                break;
            } catch (FramingValidationException ve) {
                validationError = ve.getMessage();
                log.info("Slartibartfast id='{}' FRAMING attempt {} validation failed: {}",
                        process.getId(), attempt, validationError);
                if (attempt < MAX_OUTPUT_CORRECTIONS) {
                    messages.add(AiMessage.from(text));
                    messages.add(UserMessage.from(buildCorrectivePrompt(validationError)));
                }
            }
        }

        if (parsed == null) {
            state.setFailureReason("FRAMING failed after "
                    + MAX_OUTPUT_CORRECTIONS + " corrections — last error: "
                    + validationError);
            appendIteration(state, "user-description=" + abbrev(state.getUserDescription(), 60),
                    "FAILED — " + validationError,
                    PhaseIteration.IterationOutcome.FAILED,
                    /*llmRecordId*/ latestLlmRecordId(state));
            return;
        }

        applyToState(state, parsed);
        appendIteration(state,
                "user-description=" + abbrev(state.getUserDescription(), 60),
                parsed.statedCriteria.size() + " stated, "
                        + parsed.assumedCriteria.size() + " assumed",
                PhaseIteration.IterationOutcome.PASSED,
                latestLlmRecordId(state));
    }

    // ──────────────────── Prompt building ────────────────────

    private static String buildInitialUserPrompt(ArchitectState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("User request:\n").append(state.getUserDescription()).append("\n\n");
        sb.append("Output schema type (informational, influences "
                + "the acceptance criteria): ")
                .append(state.getOutputSchemaType().name()).append("\n\n");
        sb.append("Now emit a single JSON object matching the schema.");
        return sb.toString();
    }

    private static String buildCorrectivePrompt(String validationError) {
        return "Your last JSON was rejected: " + validationError
                + "\n\nCorrect it and emit a single JSON object "
                + "matching the schema defined above.";
    }

    // ──────────────────── Parse + validate ────────────────────

    @SuppressWarnings("unchecked")
    private FramingResult parseAndValidate(String text) {
        String jsonOnly = extractJsonObject(text);
        if (jsonOnly == null) {
            throw new FramingValidationException(
                    "no JSON object found in reply");
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(jsonOnly, Map.class);
        } catch (RuntimeException e) {
            throw new FramingValidationException(
                    "JSON parse error: " + e.getMessage());
        }

        Object framedRaw = root.get("framed");
        if (!(framedRaw instanceof String framed) || framed.isBlank()) {
            throw new FramingValidationException(
                    "required field 'framed' missing or blank");
        }

        Object statedRaw = root.get("statedCriteria");
        if (!(statedRaw instanceof List<?> statedList)) {
            throw new FramingValidationException(
                    "required field 'statedCriteria' missing or not an array");
        }
        List<ParsedStated> statedCriteria = new ArrayList<>();
        for (int i = 0; i < statedList.size(); i++) {
            Object entry = statedList.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new FramingValidationException(
                        "statedCriteria[" + i + "] is not an object");
            }
            Object t = ((Map<String, Object>) entryMap).get("text");
            if (!(t instanceof String s) || s.isBlank()) {
                throw new FramingValidationException(
                        "statedCriteria[" + i + "].text missing or blank");
            }
            statedCriteria.add(new ParsedStated(s.trim()));
        }

        Object assumedRaw = root.get("assumedCriteria");
        if (!(assumedRaw instanceof List<?> assumedList)) {
            throw new FramingValidationException(
                    "required field 'assumedCriteria' missing or not an array "
                            + "(empty array is ok, but must be present)");
        }
        List<ParsedAssumed> assumedCriteria = new ArrayList<>();
        for (int i = 0; i < assumedList.size(); i++) {
            Object entry = assumedList.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "] is not an object");
            }
            Map<String, Object> m = (Map<String, Object>) entryMap;
            Object t = m.get("text");
            if (!(t instanceof String s) || s.isBlank()) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].text missing or blank");
            }
            Object o = m.get("origin");
            if (!(o instanceof String origin) || origin.isBlank()) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].origin missing");
            }
            CriterionOrigin parsedOrigin;
            try {
                parsedOrigin = CriterionOrigin.valueOf(origin.trim());
            } catch (IllegalArgumentException ex) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].origin '" + origin
                                + "' is not a valid value (allowed: "
                                + "INFERRED_CONVENTION | INFERRED_DOMAIN | INFERRED_CONTEXT)");
            }
            if (parsedOrigin == CriterionOrigin.USER_STATED
                    || parsedOrigin == CriterionOrigin.USER_CONFIRMED
                    || parsedOrigin == CriterionOrigin.DEFAULT) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].origin '" + origin
                                + "' is not allowed for inferred criteria — "
                                + "use INFERRED_CONVENTION/DOMAIN/CONTEXT");
            }
            Object c = m.get("confidence");
            double confidence;
            if (c instanceof Number n) {
                confidence = n.doubleValue();
            } else {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].confidence missing or "
                                + "not a number");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].confidence " + confidence
                                + " outside 0.0..1.0");
            }
            Object r = m.get("rationale");
            if (!(r instanceof String rationale) || rationale.isBlank()) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].rationale missing or blank");
            }
            assumedCriteria.add(new ParsedAssumed(
                    s.trim(), parsedOrigin, confidence, rationale.trim()));
        }

        return new FramingResult(framed.trim(), statedCriteria, assumedCriteria);
    }

    /** Extract the first balanced top-level JSON object. Tolerates
     *  whitespace and incidental text outside the braces. */
    private static @Nullable String extractJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    // ──────────────────── State application ────────────────────

    private void applyToState(ArchitectState state, FramingResult parsed) {
        // Engine assigns ids — the LLM only provides content. Avoids
        // collision when re-prompted output has different id-shapes.
        int criterionSeq = 1;

        List<Criterion> stated = new ArrayList<>(parsed.statedCriteria.size());
        for (ParsedStated p : parsed.statedCriteria) {
            stated.add(Criterion.builder()
                    .id("cr" + criterionSeq++)
                    .text(p.text)
                    .origin(CriterionOrigin.USER_STATED)
                    .confidence(1.0)
                    .build());
        }

        List<Rationale> existingRationales = state.getRationales();
        int rationaleSeq = existingRationales.size() + 1;
        List<Rationale> newRationales = new ArrayList<>(existingRationales);

        List<Criterion> assumed = new ArrayList<>(parsed.assumedCriteria.size());
        for (ParsedAssumed p : parsed.assumedCriteria) {
            String rationaleId = "rt" + rationaleSeq++;
            newRationales.add(Rationale.builder()
                    .id(rationaleId)
                    .text(p.rationale)
                    .inferredAt(ArchitectStatus.FRAMING)
                    .build());
            assumed.add(Criterion.builder()
                    .id("cr" + criterionSeq++)
                    .text(p.text)
                    .origin(p.origin)
                    .confidence(p.confidence)
                    .rationaleId(rationaleId)
                    .build());
        }

        state.setRationales(newRationales);
        state.setGoal(FramedGoal.builder()
                .framed(parsed.framed)
                .sourceUserText(state.getUserDescription())
                .statedCriteria(stated)
                .assumedCriteria(assumed)
                .build());
    }

    // ──────────────────── Audit append ────────────────────

    private static void appendLlmRecord(
            ArchitectState state,
            String response,
            String modelAlias,
            long durationMs,
            int attempt) {
        List<LlmCallRecord> records = new ArrayList<>(state.getLlmCallRecords());
        String id = "llm" + (records.size() + 1);
        records.add(LlmCallRecord.builder()
                .id(id)
                .phase(ArchitectStatus.FRAMING)
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
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome,
            @Nullable String llmRecordId) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.FRAMING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.FRAMING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .llmCallRecordId(llmRecordId)
                .build());
        state.setIterations(log);
    }

    // ──────────────────── Internal records ────────────────────

    private record FramingResult(
            String framed,
            List<ParsedStated> statedCriteria,
            List<ParsedAssumed> assumedCriteria) {}

    private record ParsedStated(String text) {}

    private record ParsedAssumed(
            String text,
            CriterionOrigin origin,
            double confidence,
            String rationale) {}

    private static class FramingValidationException extends RuntimeException {
        FramingValidationException(String message) { super(message); }
    }

    // ──────────────────── Small utilities ────────────────────

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
            // SHA-256 is mandatory — should never throw.
            throw new IllegalStateException(e);
        }
    }
}
