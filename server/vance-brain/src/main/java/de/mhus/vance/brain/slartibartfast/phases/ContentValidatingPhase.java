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

    /** Total character budget across all artifacts in the judge
     *  prompt. Replaces the older top-N + per-doc truncation
     *  scheme which silently dropped substantial documents
     *  ({@code essay/final-essay.md} cut at 4k of 11k). The
     *  budget is filled in path-depth-then-size order so the
     *  most likely "main deliverable" (typically shallow) lands
     *  fully before nested intermediates. */
    private static final int TOTAL_BUDGET_CHARS = 40_000;

    /** Per-document hard cap — even with budget to spare we don't
     *  let one giant doc eat the entire prompt. Long docs get
     *  truncated to this; smaller docs land fully. */
    private static final int PER_DOC_CAP_CHARS = 20_000;

    /** Recovery-count thresholds at which the content judge
     *  relaxes. {@code "partial"} verdicts are treated as failing
     *  recoveries below this count; at or above it, only outright
     *  {@code "no"} verdicts trigger recovery. The point: subjective
     *  criteria (tone, balance) can flip yes/partial across runs
     *  due to LLM jitter — letting them oscillate for the entire
     *  recovery budget is wasted compute. */
    private static final int PARTIAL_TOLERATED_AT_RECOVERY = 4;

    /** Recovery count past which the content judge skips entirely
     *  and falls back to structural validation only. Hard cap on
     *  how long the loop will keep paying for subjective re-judges. */
    private static final int SKIP_JUDGE_AT_RECOVERY = 7;

    /** {@code engineParams[JUDGE_TEMPERATURE_KEY]} — temperature
     *  override for the judge LLM call. Default {@code 0.0} for
     *  deterministic verdicts (same content → same yes/no/partial
     *  across re-runs). Independent of the recipe-level
     *  {@code temperature} which drives the worker phases. */
    public static final String JUDGE_TEMPERATURE_KEY = "judgeTemperature";

    /** Re-prompt budget for malformed-JSON corrections. */
    private static final int MAX_CORRECTIONS = 2;

    /** Path prefixes excluded from the "produced output" set —
     *  not the artifact under test.
     *  Note: {@code manuals/} and {@code skills/} are kept as legacy
     *  fall-throughs for any documents that still sit at the old
     *  pre-migration root paths; the canonical layout now puts them
     *  under {@code _vance/}, which the first entry already covers. */
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
    private final de.mhus.vance.brain.context.LanguageContextResolver languageContextResolver;

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

        // Tolerance ramp: past a threshold of recovery cycles
        // accept whatever structural validation gave us and let
        // Slart converge. Subjective criteria oscillate; without
        // this ramp the recovery budget gets eaten by yes/partial
        // jitter rather than real bugs.
        if (state.getRecoveryCount() >= SKIP_JUDGE_AT_RECOVERY) {
            log.info("Slartibartfast id='{}' content-validation SKIPPED — "
                            + "recoveryCount={} >= SKIP_JUDGE_AT_RECOVERY={}, "
                            + "passing through (only structural fails will "
                            + "still trigger recovery)",
                    process.getId(), state.getRecoveryCount(),
                    SKIP_JUDGE_AT_RECOVERY);
            appendIteration(state, userCriteria.size() + " user criteria",
                    "tolerance-ramp: judge skipped at recovery "
                            + state.getRecoveryCount(),
                    PhaseIteration.IterationOutcome.PASSED);
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

        // Tolerance-ramp stage 2: at mid-cycle "partial" verdicts
        // stop counting as fails. Subjective wobble (yes flipping
        // to partial because the model re-reads a sentence
        // differently) shouldn't reset the loop after we've made
        // good progress.
        boolean partialIsAcceptable =
                state.getRecoveryCount() >= PARTIAL_TOLERATED_AT_RECOVERY;
        List<Verdict> unsatisfied = new ArrayList<>();
        List<Verdict> satisfied = new ArrayList<>();
        for (Verdict v : verdicts) {
            boolean fails = "no".equalsIgnoreCase(v.satisfied)
                    || ("partial".equalsIgnoreCase(v.satisfied)
                            && !partialIsAcceptable);
            if (fails) {
                unsatisfied.add(v);
            } else {
                satisfied.add(v);
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
                unsatisfied,
                satisfied);
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
        // Sort by (path-depth ASC, size DESC). Shallow paths are
        // typically the "main deliverable" (essay/final-essay.md
        // beats essay/chapters/01-intro.md; report.md at the
        // project root beats research/sources/foo.md). Within a
        // depth, bigger files first — those are likely the
        // substantive outputs vs. small index/sidecar files.
        output.sort((a, b) -> {
            int da = pathDepth(a.getPath());
            int db = pathDepth(b.getPath());
            if (da != db) return Integer.compare(da, db);
            return Integer.compare(
                    b.getInlineText().length(),
                    a.getInlineText().length());
        });
        return output;
    }

    private static int pathDepth(String path) {
        if (path == null || path.isEmpty()) return 0;
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') depth++;
        }
        return depth;
    }

    private @Nullable List<Verdict> runJudge(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<Criterion> userCriteria,
            List<DocumentDocument> artifacts) {
        // Build judge-specific chat options: temperature default
        // 0.0 (deterministic verdicts; same content → same yes/no
        // verdict). lockSampling=true tells applySamplingParams not
        // to overwrite with engineParams.temperature which drives
        // the worker phases (where creativity may help).
        double judgeTemperature = readJudgeTemperature(process);
        de.mhus.vance.brain.ai.AiChatOptions judgeOptions =
                de.mhus.vance.brain.ai.AiChatOptions.builder()
                        .temperature(judgeTemperature)
                        .lockSampling(true)
                        .build();
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, ENGINE_NAME,
                        judgeOptions);
        String modelAlias = bundle.primaryConfig().provider() + ":"
                + bundle.primaryConfig().modelName();

        List<ChatMessage> messages = new ArrayList<>();
        String langBlock = languageContextResolver.formatBlock(process);
        messages.add(SystemMessage.from(langBlock.isEmpty()
                ? SYSTEM_PROMPT
                : SYSTEM_PROMPT + "\n\n" + langBlock));
        messages.add(UserMessage.from(buildUserPrompt(userCriteria, artifacts)));

        for (int attempt = 0; attempt <= MAX_CORRECTIONS; attempt++) {
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

        // Fill total budget in priority order (already sorted in
        // loadOutputArtifacts). Each doc gets full content unless
        // that would exceed PER_DOC_CAP or push past
        // TOTAL_BUDGET. When budget runs out, remaining docs are
        // listed by path only so the LLM knows they exist.
        int remainingBudget = TOTAL_BUDGET_CHARS;
        List<DocumentDocument> deferredByName = new ArrayList<>();
        for (DocumentDocument doc : artifacts) {
            String full = doc.getInlineText();
            int trueLen = full.length();
            if (remainingBudget <= 500) {
                deferredByName.add(doc);
                continue;
            }
            int cap = Math.min(PER_DOC_CAP_CHARS, remainingBudget);
            String content;
            if (trueLen <= cap) {
                content = full;
            } else {
                content = full.substring(0, cap)
                        + "\n…[truncated, "
                        + (trueLen - cap) + " chars omitted]";
            }
            sb.append("=== ").append(doc.getPath()).append(" (")
                    .append(trueLen).append(" chars total)")
                    .append(" ===\n").append(content).append("\n\n");
            remainingBudget -= Math.min(trueLen, cap);
        }
        if (!deferredByName.isEmpty()) {
            sb.append("=== Additional artifacts exist (judge by name "
                    + "only — budget exhausted) ===\n");
            for (DocumentDocument doc : deferredByName) {
                sb.append("- ").append(doc.getPath()).append(" (")
                        .append(doc.getInlineText().length())
                        .append(" chars, content not shown)\n");
            }
            sb.append("\n");
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
        recordUnsatisfied(state, message, allUserCriteria, unsatisfied,
                List.of());
    }

    private void recordUnsatisfied(
            ArchitectState state,
            String message,
            List<Criterion> allUserCriteria,
            List<Verdict> unsatisfied,
            List<Verdict> satisfied) {
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
                .hint(buildRecoveryHint(allUserCriteria, unsatisfied, satisfied))
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
            List<Criterion> allUserCriteria,
            List<Verdict> unsatisfied,
            List<Verdict> satisfied) {
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
        // Show what's already OK first — the LLM must KEEP these
        // in the revised recipe. Without this hint the model
        // tends to whack-a-mole: fix the failing criterion and
        // accidentally break a previously-satisfied one.
        if (!satisfied.isEmpty()) {
            sb.append("✓ ALREADY SATISFIED — DO NOT BREAK these "
                    + "in the revision (the relevant recipe phases "
                    + "should be kept as-is):\n");
            for (Verdict v : satisfied) {
                String text = idToText.getOrDefault(v.id, "(unknown)");
                sb.append("- ").append(v.id).append(": \"")
                        .append(text).append("\"\n");
            }
            sb.append("\n");
        }
        sb.append("✗ UNSATISFIED — fix ONLY these (verdict + judge "
                + "reasoning):\n");
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
        sb.append("\nThe ✓-list above represents working parts of "
                + "the previous recipe — preserve the phases that "
                + "produced them. The ✗-list is your sole target.");
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

    private static double readJudgeTemperature(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return 0.0;
        Object v = p.get(JUDGE_TEMPERATURE_KEY);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); }
            catch (NumberFormatException ignored) { return 0.0; }
        }
        return 0.0;
    }
}
