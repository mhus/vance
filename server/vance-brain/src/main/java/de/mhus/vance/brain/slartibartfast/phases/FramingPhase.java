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
            Du bist der FRAMING-Knoten der Slartibartfast-Engine. Dein
            einziger Job: aus der User-Beschreibung zwei Listen von
            Acceptance-Kriterien produzieren — was wörtlich gesagt wurde
            und was der User wahrscheinlich auch erwartet, ohne es zu
            sagen.

            HARTER OUTPUT-VERTRAG:
            - Beende deinen Reply mit GENAU einem JSON-Objekt.
            - KEIN Markdown-Codeblock (kein ```json … ```).
            - KEIN erklärender Text VOR dem JSON.
            - KEIN Text NACH dem JSON.

            Schema (alle Felder Pflicht außer wo markiert):
                {
                  "framed":          "<re-formulierter Auftrag, ein Satz>",
                  "statedCriteria":  [
                    { "text": "<Predicate>" }
                  ],
                  "assumedCriteria": [
                    {
                      "text":       "<Predicate>",
                      "origin":     "INFERRED_CONVENTION" |
                                    "INFERRED_DOMAIN" |
                                    "INFERRED_CONTEXT",
                      "confidence": <0.0..1.0>,
                      "rationale":  "<Warum vermutest du das?>"
                    }
                  ]
                }

            statedCriteria: nur was im User-Text wörtlich oder als
            unmissverständliche Paraphrase steht. Origin implizit
            USER_STATED, keine confidence/rationale.

            assumedCriteria: was der User mitmeint aber nicht sagt.
            - INFERRED_CONVENTION: generelle Convention (z.B.
              "schreibe X" impliziert "speicher X als Datei").
            - INFERRED_DOMAIN: domain-typische Erwartung (z.B.
              "Kapitel" impliziert "3-7 Kapitel").
            - INFERRED_CONTEXT: Kontext-Inferenz (z.B. "User schreibt
              Deutsch" impliziert "Output Deutsch").

            confidence-Skala (sei konservativ):
              0.95+ = "fast sicher" (Standard-Convention, klare Sprache)
              0.70  = "wahrscheinlich"
              0.50  = "möglich aber nicht sicher"
              0.30  = "Vermutung"

            Lieber WENIGE aber gut begründete Annahmen als viele
            schwach begründete. Wenn keine Annahmen plausibel sind,
            liefere `assumedCriteria: []`.

            Wenn du diesen Vertrag verletzt, lehnt der Validator
            deinen Output ab und du wirst um eine Korrektur gebeten.
            Liefere lieber beim ersten Mal ein gültiges JSON.
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
        sb.append("User-Auftrag:\n").append(state.getUserDescription()).append("\n\n");
        sb.append("Output-Schema-Typ (informational, beeinflusst die ")
                .append("Acceptance-Kriterien): ")
                .append(state.getOutputSchemaType().name()).append("\n\n");
        sb.append("Liefere JETZT ein einzelnes JSON-Objekt nach Schema.");
        return sb.toString();
    }

    private static String buildCorrectivePrompt(String validationError) {
        return "Dein letztes JSON wurde abgelehnt: " + validationError
                + "\n\nKorrigiere und liefere erneut ein einzelnes "
                + "JSON-Objekt nach dem oben definierten Schema.";
    }

    // ──────────────────── Parse + validate ────────────────────

    @SuppressWarnings("unchecked")
    private FramingResult parseAndValidate(String text) {
        String jsonOnly = extractJsonObject(text);
        if (jsonOnly == null) {
            throw new FramingValidationException(
                    "kein JSON-Objekt im Reply gefunden");
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(jsonOnly, Map.class);
        } catch (RuntimeException e) {
            throw new FramingValidationException(
                    "JSON-Parse-Fehler: " + e.getMessage());
        }

        Object framedRaw = root.get("framed");
        if (!(framedRaw instanceof String framed) || framed.isBlank()) {
            throw new FramingValidationException(
                    "Pflichtfeld 'framed' fehlt oder ist leer");
        }

        Object statedRaw = root.get("statedCriteria");
        if (!(statedRaw instanceof List<?> statedList)) {
            throw new FramingValidationException(
                    "Pflichtfeld 'statedCriteria' fehlt oder ist kein Array");
        }
        List<ParsedStated> statedCriteria = new ArrayList<>();
        for (int i = 0; i < statedList.size(); i++) {
            Object entry = statedList.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new FramingValidationException(
                        "statedCriteria[" + i + "] ist kein Objekt");
            }
            Object t = ((Map<String, Object>) entryMap).get("text");
            if (!(t instanceof String s) || s.isBlank()) {
                throw new FramingValidationException(
                        "statedCriteria[" + i + "].text fehlt oder ist leer");
            }
            statedCriteria.add(new ParsedStated(s.trim()));
        }

        Object assumedRaw = root.get("assumedCriteria");
        if (!(assumedRaw instanceof List<?> assumedList)) {
            throw new FramingValidationException(
                    "Pflichtfeld 'assumedCriteria' fehlt oder ist kein Array "
                            + "(empty array ist ok, aber muss vorhanden sein)");
        }
        List<ParsedAssumed> assumedCriteria = new ArrayList<>();
        for (int i = 0; i < assumedList.size(); i++) {
            Object entry = assumedList.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "] ist kein Objekt");
            }
            Map<String, Object> m = (Map<String, Object>) entryMap;
            Object t = m.get("text");
            if (!(t instanceof String s) || s.isBlank()) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].text fehlt oder ist leer");
            }
            Object o = m.get("origin");
            if (!(o instanceof String origin) || origin.isBlank()) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].origin fehlt");
            }
            CriterionOrigin parsedOrigin;
            try {
                parsedOrigin = CriterionOrigin.valueOf(origin.trim());
            } catch (IllegalArgumentException ex) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].origin '" + origin
                                + "' ist kein gültiger Wert (erlaubt: "
                                + "INFERRED_CONVENTION | INFERRED_DOMAIN | INFERRED_CONTEXT)");
            }
            if (parsedOrigin == CriterionOrigin.USER_STATED
                    || parsedOrigin == CriterionOrigin.USER_CONFIRMED
                    || parsedOrigin == CriterionOrigin.DEFAULT) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].origin '" + origin
                                + "' ist nicht für inferierte Kriterien erlaubt — "
                                + "nutze INFERRED_CONVENTION/DOMAIN/CONTEXT");
            }
            Object c = m.get("confidence");
            double confidence;
            if (c instanceof Number n) {
                confidence = n.doubleValue();
            } else {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].confidence fehlt oder "
                                + "ist keine Zahl");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].confidence " + confidence
                                + " außerhalb 0.0..1.0");
            }
            Object r = m.get("rationale");
            if (!(r instanceof String rationale) || rationale.isBlank()) {
                throw new FramingValidationException(
                        "assumedCriteria[" + i + "].rationale fehlt oder ist leer");
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
