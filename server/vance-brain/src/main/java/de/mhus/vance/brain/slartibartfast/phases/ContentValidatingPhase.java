package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.LlmCallRecord;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecoveryRequest;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Content-validation gate — LLM judge against USER_STATED /
 * USER_CONFIRMED criteria. Skipped entirely when no such criteria
 * exist (the planner inferred everything from convention, so there
 * is no user-visible "Wert lege ich auf X" target to check). When
 * present, the LLM looks at the actual produced documents and
 * reports per-criterion whether the artifact satisfies it.
 *
 * <p>Wired as a follow-up inside
 * {@link ExecutionValidatingPhase} — runs only after the structural
 * artifact-presence check passed. Failure path is the same:
 * {@code pendingRecovery → PROPOSING} with the unsatisfied
 * criteria summarised as the hint.
 *
 * <p>One LLM call per phase invocation. Output is a strict JSON
 * verdict; format errors trigger up to {@code MAX_CORRECTIONS}
 * re-prompts before giving up and emitting a graceful pass-through
 * (rather than blocking the run on the judge's own malformed JSON).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentValidatingPhase {

    private static final String ENGINE_NAME = "slartibartfast";

    public static final String RULE_USER_CRITERIA_SATISFIED =
            "user-stated-criteria-satisfied";

    /** Top-N largest output documents to send to the judge.
     *  Keeps prompt size bounded for projects with many docs. */
    private static final int MAX_ARTIFACTS_IN_PROMPT = 6;

    /** Per-document truncation in the judge prompt — full content
     *  bloats fast. Truncation marker preserved so the LLM knows
     *  it didn't see everything. */
    private static final int MAX_DOC_CHARS_IN_PROMPT = 4_000;

    /** Re-prompt budget for malformed-JSON corrections. */
    private static final int MAX_CORRECTIONS = 2;

    /** Path prefixes excluded from the "produced output" set —
     *  not the artifact under test. */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "_vance/",
            "_bin/",
            "_vogon-drafts/",
            "manuals/",
            "recipes/",
            "skills/");

    private static final String SYSTEM_PROMPT = """
            You are a strict content validator. The user requested
            an artifact (essay, plan, analysis, …) with explicit
            quality criteria. Another agent has produced one or
            more documents that are supposed to satisfy those
            criteria. Your job: judge per criterion whether the
            documents actually satisfy it.

            Output ONLY a JSON object with this shape:

            {
              "criteria": [
                {
                  "id": "<criterion-id>",
                  "satisfied": "yes" | "no" | "partial",
                  "reasoning": "<one sentence explanation>"
                }
              ]
            }

            - "yes" — the artifact clearly meets this criterion.
            - "no"  — the artifact misses or contradicts it.
            - "partial" — the artifact addresses the criterion but
              with material gaps (too short, missing aspects,
              wrong tone, etc.). Treat "partial" as a soft fail —
              the recipe will be revised.

            Do not invent criteria. Report a verdict for EVERY
            criterion-id you are given, in the same order.

            If you violate this contract the validator rejects
            your output and asks you to correct it.
            """;

    private final DocumentService documentService;
    private final EngineChatFactory engineChatFactory;
    private final LlmCallTracker llmCallTracker;
    private final ObjectMapper objectMapper;

    /**
     * Returns {@code true} when content validation actually ran
     * (criteria present, judge succeeded). {@code false} when
     * skipped (no criteria) — caller treats that as a pass.
     *
     * <p>On unsatisfied criteria, mutates {@code state} with a
     * {@link RecoveryRequest} routing back to PROPOSING. On all-
     * satisfied, appends a PASSED iteration.
     */
    public boolean executeIfApplicable(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        List<Criterion> userCriteria = collectUserCriteria(state);
        if (userCriteria.isEmpty()) {
            log.info("Slartibartfast id='{}' content-validation skipped — "
                            + "no USER_STATED/USER_CONFIRMED criteria",
                    process.getId());
            return false;
        }

        List<DocumentDocument> artifacts = loadOutputArtifacts(
                process.getTenantId(), process.getProjectId());
        if (artifacts.isEmpty()) {
            // No output documents — but structural check should
            // have caught this. Defensive: set a recovery so the
            // recipe gets another shot.
            recordUnsatisfied(state,
                    "No output documents found to validate against.",
                    userCriteria,
                    /*verdicts*/ List.of());
            return true;
        }

        List<Verdict> verdicts = runJudge(state, process, ctx,
                userCriteria, artifacts);
        if (verdicts == null) {
            // Judge failed to produce parseable JSON after retries.
            // Don't block the run on the judge's own brittleness —
            // log + pass through.
            log.warn("Slartibartfast id='{}' content-validation: judge "
                            + "produced no parseable verdict after {} "
                            + "tries; passing through",
                    process.getId(), MAX_CORRECTIONS + 1);
            appendIteration(state, userCriteria.size() + " user criteria",
                    "judge-malformed, passed through",
                    PhaseIteration.IterationOutcome.PASSED);
            return true;
        }

        List<Verdict> unsatisfied = new ArrayList<>();
        for (Verdict v : verdicts) {
            if ("no".equalsIgnoreCase(v.satisfied)
                    || "partial".equalsIgnoreCase(v.satisfied)) {
                unsatisfied.add(v);
            }
        }

        ValidationCheck check = ValidationCheck.builder()
                .rule(RULE_USER_CRITERIA_SATISFIED)
                .passed(unsatisfied.isEmpty())
                .message(buildCheckMessage(verdicts, unsatisfied))
                .build();
        List<ValidationCheck> report = new ArrayList<>(state.getValidationReport());
        report.add(check);
        state.setValidationReport(report);

        if (unsatisfied.isEmpty()) {
            appendIteration(state, userCriteria.size() + " user criteria",
                    "all satisfied — passed",
                    PhaseIteration.IterationOutcome.PASSED);
            log.info("Slartibartfast id='{}' content-validation passed — "
                            + "{} user criteria all satisfied",
                    process.getId(), userCriteria.size());
            return true;
        }

        recordUnsatisfied(state,
                buildCheckMessage(verdicts, unsatisfied),
                userCriteria,
                unsatisfied);
        log.info("Slartibartfast id='{}' content-validation failed — "
                        + "{} of {} user criteria unsatisfied, "
                        + "requesting PROPOSING re-run",
                process.getId(), unsatisfied.size(), userCriteria.size());
        return true;
    }

    // ──────────────────── helpers ────────────────────

    private List<Criterion> collectUserCriteria(ArchitectState state) {
        List<Criterion> out = new ArrayList<>();
        // Working set after CONFIRMING — primary source. Falls
        // back to FRAMING's statedCriteria if acceptanceCriteria
        // is empty (e.g., when CONFIRMING didn't run yet).
        List<Criterion> primary = state.getAcceptanceCriteria();
        if (primary == null || primary.isEmpty()) {
            if (state.getGoal() != null) {
                primary = state.getGoal().getStatedCriteria();
            }
        }
        if (primary == null) return out;
        for (Criterion c : primary) {
            if (c.getOrigin() == CriterionOrigin.USER_STATED
                    || c.getOrigin() == CriterionOrigin.USER_CONFIRMED) {
                out.add(c);
            }
        }
        return out;
    }

    private List<DocumentDocument> loadOutputArtifacts(
            String tenantId, String projectId) {
        List<DocumentDocument> all = documentService.listByProject(
                tenantId, projectId);
        List<DocumentDocument> output = new ArrayList<>();
        for (DocumentDocument doc : all) {
            String path = doc.getPath();
            if (path == null) continue;
            boolean excluded = false;
            for (String prefix : EXCLUDED_PREFIXES) {
                if (path.startsWith(prefix)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) continue;
            if (doc.getInlineText() == null
                    || doc.getInlineText().isBlank()) continue;
            output.add(doc);
        }
        output.sort(Comparator.comparingInt(
                (DocumentDocument d) -> d.getInlineText().length()).reversed());
        if (output.size() > MAX_ARTIFACTS_IN_PROMPT) {
            output = output.subList(0, MAX_ARTIFACTS_IN_PROMPT);
        }
        return output;
    }

    private @Nullable List<Verdict> runJudge(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<Criterion> userCriteria,
            List<DocumentDocument> artifacts) {
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, ENGINE_NAME);
        String modelAlias = bundle.primaryConfig().provider() + ":"
                + bundle.primaryConfig().modelName();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildUserPrompt(userCriteria, artifacts)));

        for (int attempt = 0; attempt <= MAX_CORRECTIONS; attempt++) {
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
                return parseVerdicts(text, userCriteria);
            } catch (RuntimeException e) {
                if (attempt == MAX_CORRECTIONS) return null;
                messages.add(AiMessage.from(text));
                messages.add(UserMessage.from(
                        "Your previous response was rejected: "
                                + e.getMessage()
                                + ". Emit ONLY the JSON object with "
                                + "the exact shape described in the "
                                + "system prompt. Try again."));
            }
        }
        return null;
    }

    private String buildUserPrompt(
            List<Criterion> userCriteria,
            List<DocumentDocument> artifacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER CRITERIA (judge each one separately):\n\n");
        for (Criterion c : userCriteria) {
            sb.append("- ").append(c.getId()).append(": ")
                    .append(c.getText()).append('\n');
        }
        sb.append("\nPRODUCED ARTIFACTS:\n\n");
        for (DocumentDocument doc : artifacts) {
            String content = doc.getInlineText();
            boolean truncated = content.length() > MAX_DOC_CHARS_IN_PROMPT;
            if (truncated) {
                content = content.substring(0, MAX_DOC_CHARS_IN_PROMPT)
                        + "\n…[truncated, "
                        + (doc.getInlineText().length() - MAX_DOC_CHARS_IN_PROMPT)
                        + " chars omitted]";
            }
            sb.append("=== ").append(doc.getPath()).append(" (")
                    .append(doc.getInlineText().length())
                    .append(" chars total)").append(" ===\n")
                    .append(content).append("\n\n");
        }
        sb.append("Now emit the JSON verdict.");
        return sb.toString();
    }

    private List<Verdict> parseVerdicts(
            String text, List<Criterion> userCriteria) {
        String json = extractJsonObject(text);
        if (json == null) {
            throw new RuntimeException("no JSON object found in response");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("not valid JSON: " + e.getMessage());
        }
        JsonNode criteriaArr = root.get("criteria");
        if (criteriaArr == null || !criteriaArr.isArray()) {
            throw new RuntimeException("missing 'criteria' array");
        }
        List<Verdict> out = new ArrayList<>();
        for (int i = 0; i < criteriaArr.size(); i++) {
            JsonNode entry = criteriaArr.get(i);
            String id = entry.get("id") == null
                    ? null : entry.get("id").asText();
            String satisfied = entry.get("satisfied") == null
                    ? null : entry.get("satisfied").asText();
            String reasoning = entry.get("reasoning") == null
                    ? null : entry.get("reasoning").asText();
            if (id == null || satisfied == null) {
                throw new RuntimeException(
                        "criteria[" + i + "] missing id / satisfied");
            }
            if (!"yes".equalsIgnoreCase(satisfied)
                    && !"no".equalsIgnoreCase(satisfied)
                    && !"partial".equalsIgnoreCase(satisfied)) {
                throw new RuntimeException(
                        "criteria[" + i + "].satisfied '" + satisfied
                                + "' is not one of yes/no/partial");
            }
            out.add(new Verdict(id, satisfied,
                    reasoning == null ? "" : reasoning));
        }
        return out;
    }

    /** Same brace-counting extractor as FramingPhase. */
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

    private void recordUnsatisfied(
            ArchitectState state,
            String message,
            List<Criterion> allUserCriteria,
            List<Verdict> unsatisfied) {
        ValidationCheck check = ValidationCheck.builder()
                .rule(RULE_USER_CRITERIA_SATISFIED)
                .passed(false)
                .message(message)
                .build();
        List<ValidationCheck> report = new ArrayList<>(state.getValidationReport());
        // Replace existing entry for the same rule if present
        report.removeIf(c -> RULE_USER_CRITERIA_SATISFIED.equals(c.getRule()));
        report.add(check);
        state.setValidationReport(report);

        state.setPendingRecovery(RecoveryRequest.builder()
                .fromPhase(ArchitectStatus.EXECUTION_VALIDATING)
                .toPhase(ArchitectStatus.PROPOSING)
                .reason(RULE_USER_CRITERIA_SATISFIED)
                .hint(buildRecoveryHint(allUserCriteria, unsatisfied))
                .build());

        appendIteration(state, allUserCriteria.size() + " user criteria",
                "FAILED — " + unsatisfied.size() + " unsatisfied; "
                        + "rollback to PROPOSING",
                PhaseIteration.IterationOutcome.REQUESTED_RECOVERY);
    }

    private String buildCheckMessage(
            List<Verdict> verdicts, List<Verdict> unsatisfied) {
        if (unsatisfied.isEmpty()) {
            return verdicts.size() + " user criteria all satisfied";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(unsatisfied.size()).append(" of ")
                .append(verdicts.size())
                .append(" user criteria not satisfied: ");
        for (int i = 0; i < unsatisfied.size(); i++) {
            Verdict v = unsatisfied.get(i);
            if (i > 0) sb.append("; ");
            sb.append(v.id).append("=").append(v.satisfied);
        }
        return sb.toString();
    }

    private String buildRecoveryHint(
            List<Criterion> allUserCriteria, List<Verdict> unsatisfied) {
        // Build an id-to-text lookup so the hint surfaces what
        // each missing criterion ACTUALLY asked for, not just
        // the cryptic id.
        Map<String, String> idToText = new LinkedHashMap<>();
        for (Criterion c : allUserCriteria) {
            idToText.put(c.getId(), c.getText());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("The previously generated recipe ran to completion "
                + "but the produced artifacts do not satisfy all "
                + "user-stated quality criteria.\n\n");
        sb.append("Unsatisfied criteria (verdict + judge reasoning):\n");
        for (Verdict v : unsatisfied) {
            String text = idToText.getOrDefault(v.id, "(unknown)");
            sb.append("- ").append(v.id).append(" [").append(v.satisfied)
                    .append("] \"").append(text).append("\"\n");
            sb.append("  judge: ").append(v.reasoning).append("\n");
        }
        sb.append("\nPOSSIBLE OPTIONS to fix this — choose one or "
                + "combine:\n");
        sb.append("- Add a dedicated review/lektorat phase AFTER the "
                + "drafting phases that explicitly enforces the failed "
                + "criteria — e.g. 'check every chapter against criterion "
                + "X and rewrite if not met'. Use worker 'ford' with "
                + "doc_edit for in-place fixes.\n");
        sb.append("- Strengthen the workerInput of the phase that "
                + "produces the relevant artifact: name the failed "
                + "criterion in its workerInput verbatim, plus a "
                + "concrete 'MUST'-instruction (e.g. 'jede Quelle "
                + "MUSS in (vgl. ..., JJJJ)-Form zitiert sein').\n");
        sb.append("- If a criterion is about FORMAT (citations, "
                + "headings, length): emit a phase whose only job is "
                + "to scan the existing chapter docs and rewrite any "
                + "violations.\n");
        sb.append("- If a criterion is about TONE/STYLE: add a "
                + "proofreading phase with worker 'ford' that reads "
                + "each chapter and rewrites style violations "
                + "(umgangssprachlich, wertend, etc.) using doc_edit.\n");
        sb.append("- If a criterion is about COVERAGE (e.g. 'must "
                + "cover Pro/Contra equally'): adjust the outline phase "
                + "to enforce symmetric chapter count per side, then "
                + "the drafting phases inherit balance from the "
                + "outline.\n");
        sb.append("\nKEEP what was satisfied: criteria that already "
                + "passed don't need new phases. Add or strengthen "
                + "only the targets above.");
        return sb.toString();
    }

    private void appendLlmRecord(
            ArchitectState state, String response, String modelAlias,
            long durationMs, int attempt) {
        int id = state.getLlmCallRecords().size() + 1;
        LlmCallRecord record = LlmCallRecord.builder()
                .id("llm" + id)
                .phase(ArchitectStatus.EXECUTION_VALIDATING)
                .iteration(attempt + 1)
                .durationMs(durationMs)
                .modelAlias(modelAlias)
                .response(response)
                .build();
        List<LlmCallRecord> records = new ArrayList<>(state.getLlmCallRecords());
        records.add(record);
        state.setLlmCallRecords(records);
    }

    private void appendIteration(
            ArchitectState state, String inputSummary,
            String outputSummary, PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase()
                        == ArchitectStatus.EXECUTION_VALIDATING)
                .count() + 1;
        PhaseIteration it = PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.EXECUTION_VALIDATING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build();
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(it);
        state.setIterations(log);
    }

    private record Verdict(String id, String satisfied, String reasoning) {}
}
