package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.ClassificationKind;
import de.mhus.vance.api.slartibartfast.EvidenceSource;
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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * CLASSIFYING phase — for every {@link EvidenceSource} from
 * GATHERING, runs one LLM call to extract atomic claims and tag
 * each as {@link ClassificationKind#FACT} /
 * {@link ClassificationKind#EXAMPLE} /
 * {@link ClassificationKind#OPINION} /
 * {@link ClassificationKind#OUTDATED}. Per-claim rationales
 * (required for non-FACT classifications) become {@link Rationale}
 * entries linked via {@link Claim#getClassificationRationaleId()}.
 *
 * <p>Hard contract per LLM call: single JSON object with a
 * {@code claims} array; each claim has text + classification +
 * optional verbatim quote + rationale text. Validation failures
 * trigger up to {@link #MAX_OUTPUT_CORRECTIONS} re-prompts per
 * source. If a source still doesn't yield a valid response past
 * the budget, the whole phase {@link PhaseIteration.IterationOutcome#FAILED}s
 * and the engine marks the run FAILED.
 *
 * <p>Idempotent on re-entry: re-running rebuilds
 * {@code evidenceClaims} from scratch off
 * {@link ArchitectState#getEvidenceSources()}. Old non-CLASSIFYING
 * rationales (FRAMING, GATHERING) are preserved; old CLASSIFYING-
 * tier rationales are dropped along with the claims that
 * referenced them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClassifyingPhase {

    private static final String ENGINE_NAME = "slartibartfast";

    private static final int MAX_OUTPUT_CORRECTIONS = 2;
    private static final int PROMPT_PREVIEW_LIMIT = 500;

    private static final String SYSTEM_PROMPT = """
            You are the CLASSIFYING node of the Slartibartfast
            engine. From the manual text you extract atomic claims
            and classify each one.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO explanatory text BEFORE the JSON.
            - NO text AFTER the JSON.

            Schema:
                {
                  "claims": [
                    {
                      "text":           "<atomic claim, paraphrased>",
                      "classification": "FACT" | "EXAMPLE" | "OPINION" | "OUTDATED",
                      "quote":          "<verbatim span from source>" | null,
                      "rationale":      "<why this classification?>" | null
                    }
                  ]
                }

            Classification scale:
            - FACT: claim holds without context — defined facts,
              hard constraints, measurable quantities, defined
              terms. Example: "Manuals live under manuals/".
            - EXAMPLE: concrete example illustrating a FACT/OPINION.
              Weaker than FACT (shows what is possible, not what
              is required). Example: "such as 'the screws were
              small, robust, and gone'".
            - OPINION: subjective recommendation, stylistic
              preference, taste-based statement. Example: "avoid
              long sentences without a punchline".
            - OUTDATED: explicitly marked as outdated.

            Atomicity:
            - ONE point per claim.
            - If a paragraph mixes two statements (one FACT, one
              OPINION), split into two separate claims.
            - Multiple examples in a list → multiple claims (unless
              the list itself is the statement).

            Rationale:
            - Required for everything except FACT.
            - Optional/null for FACT.
            - A justification of WHY the classification applies,
              NOT a repetition of the claim text.

            Quote:
            - Optional. When set, MUST be a verbatim span from the
              source text (no paraphrase).

            If the source text is empty or has no extractable
            claims, return `claims: []`.

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

        if (state.getEvidenceSources().isEmpty()) {
            // Empty-sources case: GATHERING produced nothing, so
            // there's nothing to classify. Skip silently with audit.
            state.setEvidenceClaims(List.of());
            appendIteration(state, "0 sources",
                    "0 claims (no sources to classify)",
                    PhaseIteration.IterationOutcome.PASSED);
            return;
        }

        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, ENGINE_NAME);
        String modelAlias = bundle.primaryConfig().provider() + ":"
                + bundle.primaryConfig().modelName();

        // Rebuild from scratch — CLASSIFYING re-entry must produce
        // a fresh claim set, not pile onto an old one.
        List<Claim> allClaims = new ArrayList<>();
        // Preserve non-CLASSIFYING rationales; replace the CLASSIFYING-
        // tier ones (claim-classification reasons).
        List<Rationale> rationalePool = new ArrayList<>();
        for (Rationale r : state.getRationales()) {
            if (r.getInferredAt() != ArchitectStatus.CLASSIFYING) {
                rationalePool.add(r);
            }
        }

        int claimSeq = 1;
        int rationaleSeq = rationalePool.size() + 1;
        int totalRetries = 0;

        for (EvidenceSource source : state.getEvidenceSources()) {
            ClassifyResult parsed;
            try {
                parsed = classifyOne(state, process, source, bundle, modelAlias);
                totalRetries += parsed.retries;
            } catch (ClassifyFailedException ex) {
                state.setFailureReason("CLASSIFYING failed at source '"
                        + source.getId() + "' (" + source.getPath()
                        + ") after " + MAX_OUTPUT_CORRECTIONS
                        + " corrections — last error: " + ex.getMessage());
                appendIteration(state,
                        state.getEvidenceSources().size() + " sources",
                        "FAILED at " + source.getId() + " — " + ex.getMessage(),
                        PhaseIteration.IterationOutcome.FAILED);
                state.setRationales(rationalePool);
                state.setEvidenceClaims(allClaims);
                return;
            }

            for (ParsedClaim p : parsed.claims) {
                String claimId = "cl" + claimSeq++;
                String classificationRationaleId = null;
                if (p.rationale != null && !p.rationale.isBlank()) {
                    String rid = "rt" + rationaleSeq++;
                    rationalePool.add(Rationale.builder()
                            .id(rid)
                            .text(p.rationale)
                            .sourceRefs(List.of(source.getId()))
                            .inferredAt(ArchitectStatus.CLASSIFYING)
                            .build());
                    classificationRationaleId = rid;
                }
                allClaims.add(Claim.builder()
                        .id(claimId)
                        .sourceId(source.getId())
                        .text(p.text)
                        .classification(p.classification)
                        .quote(p.quote)
                        .classificationRationaleId(classificationRationaleId)
                        .build());
            }
        }

        state.setRationales(rationalePool);
        state.setEvidenceClaims(allClaims);

        appendIteration(state,
                state.getEvidenceSources().size() + " sources",
                allClaims.size() + " claims (" + totalRetries
                        + " retry" + (totalRetries == 1 ? "" : "s") + ")",
                PhaseIteration.IterationOutcome.PASSED);

        log.info("Slartibartfast id='{}' CLASSIFYING produced {} claims "
                        + "from {} sources",
                process.getId(), allClaims.size(),
                state.getEvidenceSources().size());
    }

    // ──────────────────── Per-source LLM round-trip ────────────────────

    private ClassifyResult classifyOne(
            ArchitectState state,
            ThinkProcessDocument process,
            EvidenceSource source,
            EngineChatFactory.EngineChatBundle bundle,
            String modelAlias) {

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildInitialUserPrompt(state, source)));

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
                appendLlmRecord(state, source, text, modelAlias,
                        durationMs, attempt);
            }

            try {
                List<ParsedClaim> claims = parseAndValidate(text);
                return new ClassifyResult(claims, attempt);
            } catch (ClassifyValidationException ve) {
                validationError = ve.getMessage();
                log.info("Slartibartfast id='{}' CLASSIFYING source='{}' "
                                + "attempt {} validation failed: {}",
                        process.getId(), source.getId(), attempt, validationError);
                if (attempt < MAX_OUTPUT_CORRECTIONS) {
                    messages.add(AiMessage.from(text));
                    messages.add(UserMessage.from(buildCorrectivePrompt(validationError)));
                }
            }
        }
        throw new ClassifyFailedException(validationError);
    }

    private static String buildInitialUserPrompt(
            ArchitectState state, EvidenceSource source) {
        StringBuilder sb = new StringBuilder();
        if (state.getGoal() != null) {
            sb.append("Framed goal (context for classification):\n")
                    .append(state.getGoal().getFramed()).append("\n\n");
        }
        sb.append("Source path: ").append(source.getPath() == null
                ? "<inline>" : source.getPath()).append("\n");
        sb.append("Source type: ").append(source.getType().name()).append("\n\n");
        sb.append("Source content:\n");
        sb.append("---BEGIN SOURCE---\n");
        sb.append(source.getContent());
        sb.append("\n---END SOURCE---\n\n");
        sb.append("Now emit a single JSON object with `claims` "
                + "matching the schema. For an empty source: `claims: []`.");
        return sb.toString();
    }

    private static String buildCorrectivePrompt(String validationError) {
        return "Your last JSON was rejected: " + validationError
                + "\n\nCorrect it and emit a single JSON object with "
                + "`claims` matching the schema defined above.";
    }

    // ──────────────────── Parse + validate ────────────────────

    @SuppressWarnings("unchecked")
    private List<ParsedClaim> parseAndValidate(String text) {
        String jsonOnly = extractJsonObject(text);
        if (jsonOnly == null) {
            throw new ClassifyValidationException(
                    "no JSON object found in reply");
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(jsonOnly, Map.class);
        } catch (RuntimeException e) {
            throw new ClassifyValidationException(
                    "JSON parse error: " + e.getMessage());
        }

        Object claimsRaw = root.get("claims");
        if (!(claimsRaw instanceof List<?> claimsList)) {
            throw new ClassifyValidationException(
                    "required field 'claims' missing or not an array");
        }

        List<ParsedClaim> out = new ArrayList<>();
        for (int i = 0; i < claimsList.size(); i++) {
            Object entry = claimsList.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new ClassifyValidationException(
                        "claims[" + i + "] is not an object");
            }
            Map<String, Object> m = (Map<String, Object>) entryMap;

            Object t = m.get("text");
            if (!(t instanceof String txt) || txt.isBlank()) {
                throw new ClassifyValidationException(
                        "claims[" + i + "].text missing or blank");
            }

            Object c = m.get("classification");
            if (!(c instanceof String cls) || cls.isBlank()) {
                throw new ClassifyValidationException(
                        "claims[" + i + "].classification missing");
            }
            ClassificationKind classification;
            try {
                classification = ClassificationKind.valueOf(cls.trim());
            } catch (IllegalArgumentException ex) {
                throw new ClassifyValidationException(
                        "claims[" + i + "].classification '" + cls
                                + "' invalid (allowed: FACT | EXAMPLE | OPINION | OUTDATED)");
            }

            Object q = m.get("quote");
            String quote = null;
            if (q instanceof String qs && !qs.isBlank()) {
                quote = qs;
            }

            Object r = m.get("rationale");
            String rationale = null;
            if (r instanceof String rs && !rs.isBlank()) {
                rationale = rs.trim();
            }

            // For non-FACT, rationale is required by spec — let
            // a missing one bounce back so the LLM justifies the
            // classification. FACT may carry a null rationale.
            if (classification != ClassificationKind.FACT && rationale == null) {
                throw new ClassifyValidationException(
                        "claims[" + i + "] classification '" + classification
                                + "' requires a non-blank 'rationale' (only "
                                + "FACT may omit it)");
            }

            out.add(new ParsedClaim(txt.trim(), classification, quote, rationale));
        }
        return out;
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

    // ──────────────────── Audit append ────────────────────

    private static void appendLlmRecord(
            ArchitectState state,
            EvidenceSource source,
            String response,
            String modelAlias,
            long durationMs,
            int attempt) {
        List<LlmCallRecord> records = new ArrayList<>(state.getLlmCallRecords());
        String id = "llm" + (records.size() + 1);
        records.add(LlmCallRecord.builder()
                .id(id)
                .phase(ArchitectStatus.CLASSIFYING)
                .iteration(attempt + 1)
                .promptHash(sha256Hex(SYSTEM_PROMPT + "\n----\n"
                        + source.getId() + "\n----\n" + source.getContent()))
                .promptPreview(abbrev("source=" + source.getId() + " path="
                        + source.getPath(), PROMPT_PREVIEW_LIMIT))
                .response(response)
                .modelAlias(modelAlias)
                .durationMs(durationMs)
                .build());
        state.setLlmCallRecords(records);
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.CLASSIFYING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.CLASSIFYING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }

    // ──────────────────── Internal records / errors ────────────────────

    private record ParsedClaim(
            String text,
            ClassificationKind classification,
            @Nullable String quote,
            @Nullable String rationale) {}

    private record ClassifyResult(List<ParsedClaim> claims, int retries) {}

    private static class ClassifyValidationException extends RuntimeException {
        ClassifyValidationException(String message) { super(message); }
    }

    private static class ClassifyFailedException extends RuntimeException {
        ClassifyFailedException(String message) { super(message); }
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
