package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.LlmCallRecord;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Rationale;
import de.mhus.vance.api.slartibartfast.RecoveryRequest;
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
 * DECOMPOSING phase — turns the framed goal + acceptance criteria
 * + classified evidence into a list of {@link Subgoal}s. Each
 * subgoal either cites at least one {@link Claim#getId()} as
 * evidence OR is explicitly marked
 * {@link Subgoal#isSpeculative()} with a rationale. The hard
 * BINDING gate enforces this in the next phase.
 *
 * <p>The phase also records a {@link Rationale} for the
 * decomposition shape itself ("why N subgoals in this order"),
 * stored under
 * {@link ArchitectState#getDecompositionRationaleId()}.
 *
 * <p>Re-runs (BINDING failure → recovery rollback to DECOMPOSING)
 * receive a corrective hint in the {@link RecoveryRequest#getHint()}
 * appended to the prompt — the LLM sees the previous failed
 * decomposition AND the validator's complaint, then re-emits.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DecomposingPhase {

    private static final String ENGINE_NAME = "slartibartfast";
    private static final int MAX_OUTPUT_CORRECTIONS = 2;
    private static final int PROMPT_PREVIEW_LIMIT = 500;

    private static final String SYSTEM_PROMPT = """
            Du bist der DECOMPOSING-Knoten der Slartibartfast-Engine.
            Aus dem framed Goal, der acceptanceCriteria-Liste, den
            evidenceClaims und evidenceSources entwirfst du eine
            Liste von Subgoals — der Schritte, die der Plan ausführt.

            HARTE REGELN für jeden Subgoal:
            - JEDER Subgoal MUSS entweder
              (a) mindestens einen evidenceRef auf eine vorhandene
                  Claim-ID enthalten, ODER
              (b) explizit `speculative: true` mit non-blank
                  `speculationRationale` markiert sein.
            - JEDER Subgoal MUSS via criterionRefs auf mindestens
              ein acceptanceCriterion zeigen, das er adressiert.
            - JEDES acceptanceCriterion MUSS von mindestens einem
              Subgoal adressiert werden (Coverage-Pflicht).

            HARTER OUTPUT-VERTRAG:
            - Beende deinen Reply mit GENAU einem JSON-Objekt.
            - KEIN Markdown-Codeblock (kein ```json … ```).
            - KEIN Text vor oder nach dem JSON.

            Schema:
                {
                  "subgoals": [
                    {
                      "goal":                 "<step description>",
                      "evidenceRefs":         ["cl3","cl15"],
                      "criterionRefs":        ["cr1"],
                      "speculative":          false,
                      "speculationRationale": null
                    }
                  ],
                  "decompositionRationale": "<warum diese decomposition>"
                }

            Bei `speculative: true`:
            - `evidenceRefs` darf leer sein (`[]`).
            - `speculationRationale` MUSS gesetzt sein und erklären
              WARUM keine Evidence verfügbar ist und WAS angenommen
              wird.

            Konservativ sein:
            - Lieber wenige feste Subgoals + 1-2 speculative als
              viele schwach belegte.
            - Speculative-Quote über 30% führt zu Validation-Fail.

            decompositionRationale: 1-2 Sätze warum die Decomposition
            in genau dieser Form (Anzahl, Reihenfolge) sinnvoll ist.
            Bezieht sich auf die Plan-Shape, nicht auf einzelne
            Subgoals.
            """;

    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final ObjectMapper objectMapper;

    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        if (state.getGoal() == null) {
            state.setFailureReason("DECOMPOSING entered without a FramedGoal");
            return;
        }
        if (state.getAcceptanceCriteria().isEmpty()) {
            state.setFailureReason("DECOMPOSING entered with empty "
                    + "acceptanceCriteria — CONFIRMING must run first");
            return;
        }

        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, ENGINE_NAME);
        String modelAlias = bundle.primaryConfig().provider() + ":"
                + bundle.primaryConfig().modelName();

        // Recovery hint from a failed BINDING pass — appended to the
        // prompt so the LLM sees what was rejected and why.
        String recoveryHint = null;
        if (state.getPendingRecovery() != null
                && state.getPendingRecovery().getToPhase() == ArchitectStatus.DECOMPOSING) {
            recoveryHint = state.getPendingRecovery().getHint();
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildInitialUserPrompt(state, recoveryHint)));

        DecomposeResult parsed = null;
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
            } catch (DecomposeValidationException ve) {
                validationError = ve.getMessage();
                log.info("Slartibartfast id='{}' DECOMPOSING attempt {} validation failed: {}",
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
            state.setFailureReason("DECOMPOSING failed after "
                    + MAX_OUTPUT_CORRECTIONS + " corrections — last error: "
                    + validationError);
            appendIteration(state,
                    summariseInputs(state),
                    "FAILED — " + validationError,
                    PhaseIteration.IterationOutcome.FAILED,
                    /*triggeredBy*/ recoveryHint != null ? "recovery" : "initial",
                    latestLlmRecordId(state));
            return;
        }

        applyToState(state, parsed);

        // Consume the recovery request — we acted on its hint.
        boolean wasRecovery = state.getPendingRecovery() != null
                && state.getPendingRecovery().getToPhase() == ArchitectStatus.DECOMPOSING;
        if (wasRecovery) {
            state.setPendingRecovery(null);
        }

        appendIteration(state,
                summariseInputs(state),
                parsed.subgoals.size() + " subgoals ("
                        + countSpeculative(parsed.subgoals) + " speculative)",
                PhaseIteration.IterationOutcome.PASSED,
                wasRecovery ? "recovery" : "initial",
                latestLlmRecordId(state));
    }

    // ──────────────────── Prompt building ────────────────────

    private static String buildInitialUserPrompt(
            ArchitectState state, @Nullable String recoveryHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("Framed Goal:\n").append(state.getGoal().getFramed()).append("\n\n");

        sb.append("acceptanceCriteria (jedes muss von mindestens einem ")
                .append("Subgoal adressiert werden):\n");
        for (Criterion c : state.getAcceptanceCriteria()) {
            sb.append("  ").append(c.getId()).append(" [")
                    .append(c.getOrigin()).append("]: ")
                    .append(c.getText()).append("\n");
        }
        sb.append("\n");

        sb.append("evidenceClaims (Subgoals zitieren diese via evidenceRefs):\n");
        if (state.getEvidenceClaims().isEmpty()) {
            sb.append("  (keine — Subgoals müssen daher überwiegend "
                    + "speculative sein, falls der Plan trotzdem "
                    + "machbar ist)\n");
        } else {
            for (Claim c : state.getEvidenceClaims()) {
                sb.append("  ").append(c.getId()).append(" [")
                        .append(c.getClassification()).append(", from ")
                        .append(c.getSourceId()).append("]: ")
                        .append(c.getText()).append("\n");
            }
        }
        sb.append("\n");

        sb.append("Output-Schema-Typ (informational): ")
                .append(state.getOutputSchemaType()).append("\n");
        sb.append("maxSpeculativeRatio: ")
                .append(state.getMaxSpeculativeRatio()).append("\n\n");

        if (recoveryHint != null && !recoveryHint.isBlank()) {
            sb.append("WICHTIG — der vorige Decomposing-Versuch wurde "
                    + "abgelehnt. Korrektur-Hinweis:\n")
                    .append(recoveryHint).append("\n\n");
        }

        sb.append("Liefere JETZT ein einzelnes JSON-Objekt nach Schema. "
                + "Stelle sicher: jeder Subgoal hat entweder evidenceRefs "
                + "oder speculative=true mit Rationale; jedes "
                + "acceptanceCriterion ist von mindestens einem Subgoal "
                + "adressiert.");
        return sb.toString();
    }

    // ──────────────────── Parse + light validate ────────────────────

    @SuppressWarnings("unchecked")
    private DecomposeResult parseAndValidate(String text) {
        String jsonOnly = extractJsonObject(text);
        if (jsonOnly == null) {
            throw new DecomposeValidationException(
                    "kein JSON-Objekt im Reply gefunden");
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(jsonOnly, Map.class);
        } catch (RuntimeException e) {
            throw new DecomposeValidationException(
                    "JSON-Parse-Fehler: " + e.getMessage());
        }

        Object subgoalsRaw = root.get("subgoals");
        if (!(subgoalsRaw instanceof List<?> subgoalsList)) {
            throw new DecomposeValidationException(
                    "Pflichtfeld 'subgoals' fehlt oder ist kein Array");
        }
        if (subgoalsList.isEmpty()) {
            throw new DecomposeValidationException(
                    "subgoals darf nicht leer sein — der Plan muss mindestens "
                            + "einen Schritt enthalten");
        }

        List<ParsedSubgoal> subgoals = new ArrayList<>();
        for (int i = 0; i < subgoalsList.size(); i++) {
            Object entry = subgoalsList.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new DecomposeValidationException(
                        "subgoals[" + i + "] ist kein Objekt");
            }
            Map<String, Object> m = (Map<String, Object>) entryMap;

            Object g = m.get("goal");
            if (!(g instanceof String goal) || goal.isBlank()) {
                throw new DecomposeValidationException(
                        "subgoals[" + i + "].goal fehlt oder ist leer");
            }

            List<String> evidenceRefs = parseStringArray(
                    m.get("evidenceRefs"), "subgoals[" + i + "].evidenceRefs");
            List<String> criterionRefs = parseStringArray(
                    m.get("criterionRefs"), "subgoals[" + i + "].criterionRefs");

            boolean speculative = false;
            Object s = m.get("speculative");
            if (s instanceof Boolean b) {
                speculative = b;
            } else if (s != null) {
                throw new DecomposeValidationException(
                        "subgoals[" + i + "].speculative muss boolean sein");
            }

            String specRationale = null;
            Object sr = m.get("speculationRationale");
            if (sr instanceof String srs && !srs.isBlank()) {
                specRationale = srs.trim();
            }

            // Light validation — full referential checks happen in
            // BINDING. Here we just enforce shape+presence:
            if (speculative) {
                if (specRationale == null) {
                    throw new DecomposeValidationException(
                            "subgoals[" + i + "].speculative=true requires "
                                    + "non-blank speculationRationale");
                }
            } else {
                if (evidenceRefs.isEmpty()) {
                    throw new DecomposeValidationException(
                            "subgoals[" + i + "] is non-speculative but "
                                    + "evidenceRefs is empty — either cite "
                                    + "claims or mark speculative");
                }
            }
            if (criterionRefs.isEmpty()) {
                throw new DecomposeValidationException(
                        "subgoals[" + i + "].criterionRefs is empty — every "
                                + "subgoal must address at least one "
                                + "acceptance criterion");
            }

            subgoals.add(new ParsedSubgoal(goal.trim(), evidenceRefs,
                    criterionRefs, speculative, specRationale));
        }

        Object dr = root.get("decompositionRationale");
        if (!(dr instanceof String drs) || drs.isBlank()) {
            throw new DecomposeValidationException(
                    "Pflichtfeld 'decompositionRationale' fehlt oder ist leer");
        }

        return new DecomposeResult(subgoals, drs.trim());
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringArray(@Nullable Object raw, String label) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new DecomposeValidationException(label
                    + " muss ein Array sein (oder fehlen)");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            } else {
                throw new DecomposeValidationException(label
                        + " enthält non-string oder leeren Eintrag");
            }
        }
        return out;
    }

    // ──────────────────── State application ────────────────────

    private void applyToState(ArchitectState state, DecomposeResult parsed) {
        // Engine-assigned subgoal ids — reset on each pass so
        // recovery rollbacks don't carry stale ids forward.
        int sgSeq = 1;
        List<Subgoal> subgoals = new ArrayList<>(parsed.subgoals.size());
        for (ParsedSubgoal p : parsed.subgoals) {
            subgoals.add(Subgoal.builder()
                    .id("sg" + sgSeq++)
                    .goal(p.goal)
                    .evidenceRefs(p.evidenceRefs)
                    .criterionRefs(p.criterionRefs)
                    .speculative(p.speculative)
                    .speculationRationale(p.speculationRationale)
                    .build());
        }
        state.setSubgoals(subgoals);

        // Replace any previous DECOMPOSING-tier rationale (recovery
        // re-run); preserve everything from earlier phases.
        List<Rationale> pool = new ArrayList<>();
        for (Rationale r : state.getRationales()) {
            if (r.getInferredAt() != ArchitectStatus.DECOMPOSING) {
                pool.add(r);
            }
        }
        String decompId = "rt" + (pool.size() + 1);
        pool.add(Rationale.builder()
                .id(decompId)
                .text(parsed.decompositionRationale)
                .inferredAt(ArchitectStatus.DECOMPOSING)
                .build());
        state.setRationales(pool);
        state.setDecompositionRationaleId(decompId);
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
                .phase(ArchitectStatus.DECOMPOSING)
                .iteration(attempt + 1)
                .promptHash(sha256Hex(SYSTEM_PROMPT + "\n----\n" + state.getRunId()))
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
            String triggeredBy,
            @Nullable String llmRecordId) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.DECOMPOSING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.DECOMPOSING)
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
        return "criteria=" + state.getAcceptanceCriteria().size()
                + ", claims=" + state.getEvidenceClaims().size()
                + ", sources=" + state.getEvidenceSources().size();
    }

    private static long countSpeculative(List<ParsedSubgoal> subgoals) {
        return subgoals.stream().filter(s -> s.speculative).count();
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

    private record ParsedSubgoal(
            String goal,
            List<String> evidenceRefs,
            List<String> criterionRefs,
            boolean speculative,
            @Nullable String speculationRationale) {}

    private record DecomposeResult(
            List<ParsedSubgoal> subgoals,
            String decompositionRationale) {}

    private static class DecomposeValidationException extends RuntimeException {
        DecomposeValidationException(String message) { super(message); }
    }
}
