package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecoveryRequest;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * EXECUTION_VALIDATING — structural post-execution gate. Runs
 * after the auto-spawned execution child closed {@code DONE} and verifies
 * that the artifacts the generated recipe was supposed to produce
 * actually exist as project documents with non-trivial content.
 *
 * <p>Pure logic — no LLM. Expected paths are extracted from each
 * non-speculative {@link Subgoal}'s {@code goal} text via a regex
 * that picks up quoted {@code .md} / {@code .json} / {@code .yaml}
 * paths (the {@code "'essay/chapters/01-introduction.md'"} pattern
 * Slart's PROPOSING tends to embed). Subgoals without a parseable
 * path are skipped — they describe work that doesn't map to a
 * single output file (e.g., a final-notify-the-user step).
 *
 * <p>Failure mode: any expected path is missing or below the
 * minimum size threshold ({@code MIN_ARTIFACT_CHARS}). Builds a
 * {@link RecoveryRequest} routing back to
 * {@link ArchitectStatus#PROPOSING} with a hint listing the
 * missing artifacts. The engine's existing recovery budget
 * applies; once exhausted, ESCALATED / FAILED as usual.
 *
 * <p>Pass mode: all parsed expected paths exist with content.
 * Leaves {@code pendingRecovery} null — the engine flips to
 * {@link ArchitectStatus#DONE}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionValidatingPhase {

    /** Quoted file paths in subgoal goal text, e.g.
     *  {@code 'essay/chapters/01-introduction.md'} or
     *  {@code "essay/final-essay.md"}. */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "['\"]([a-zA-Z0-9._/-]+\\.(?:md|json|yaml|yml|txt))['\"]");

    /** Below this many characters the artifact is considered
     *  effectively empty (LLM wrote a stub or hit an error). */
    private static final int MIN_ARTIFACT_CHARS = 200;

    public static final String RULE_ARTIFACTS_PRESENT =
            "executed-recipe-produced-expected-artifacts";

    private final DocumentService documentService;
    private final ContentValidatingPhase contentValidatingPhase;

    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        if (state.getSubgoals().isEmpty()) {
            // Nothing planned structurally — still run the
            // content-judge against user criteria, since a thin
            // plan with strong user criteria is exactly where the
            // content judge adds the most value.
            contentValidatingPhase.executeIfApplicable(state, process, ctx);
            if (state.getPendingRecovery() != null) return;
            appendIteration(state, "no subgoals to validate",
                    "passed (vacuous structural; content not blocking)",
                    PhaseIteration.IterationOutcome.PASSED);
            return;
        }

        Set<String> expectedPaths = new LinkedHashSet<>();
        for (Subgoal sg : state.getSubgoals()) {
            if (sg.isSpeculative()) continue;
            String goal = sg.getGoal();
            if (goal == null) continue;
            Matcher m = PATH_PATTERN.matcher(goal);
            while (m.find()) {
                String path = m.group(1);
                // Filter out reference-only paths the plan
                // mentions for INPUT (e.g., items/research-
                // answers.md) by skipping anything under known
                // input-only folders. Heuristic — recipes can
                // legitimately reuse those, but if they're
                // pre-existing seed data the artifact is already
                // there and the check would pass anyway.
                expectedPaths.add(path);
            }
        }

        if (expectedPaths.isEmpty()) {
            // No parseable paths — structural check can't say
            // anything useful. Hand off straight to the content
            // judge if user criteria are present; otherwise pass
            // through. This is the *most important* case for the
            // content judge — exactly when structural fails to
            // give signal.
            boolean contentRan = contentValidatingPhase
                    .executeIfApplicable(state, process, ctx);
            if (state.getPendingRecovery() != null) return;
            if (!contentRan) {
                appendIteration(state, state.getSubgoals().size() + " subgoals",
                        "passed (no parseable expected paths, no user criteria)",
                        PhaseIteration.IterationOutcome.PASSED);
            }
            return;
        }

        List<String> missing = new ArrayList<>();
        List<String> tooSmall = new ArrayList<>();
        List<String> present = new ArrayList<>();
        for (String path : expectedPaths) {
            Optional<DocumentDocument> doc = documentService.findByPath(
                    process.getTenantId(), process.getProjectId(), path);
            if (doc.isEmpty()) {
                missing.add(path);
                continue;
            }
            String text = doc.get().getInlineText();
            int size = text == null ? 0 : text.length();
            if (size < MIN_ARTIFACT_CHARS) {
                tooSmall.add(path + " (" + size + " chars)");
            } else {
                present.add(path + " (" + size + " chars)");
            }
        }

        ValidationCheck check = ValidationCheck.builder()
                .rule(RULE_ARTIFACTS_PRESENT)
                .passed(missing.isEmpty() && tooSmall.isEmpty())
                .message(buildCheckMessage(expectedPaths, missing, tooSmall))
                .build();
        List<ValidationCheck> report = new ArrayList<>(state.getValidationReport());
        report.add(check);
        state.setValidationReport(report);

        if (check.isPassed()) {
            // Structural artifacts present. Hand off to the
            // content judge if the user actually provided
            // criteria — otherwise we're done.
            boolean contentRan = contentValidatingPhase
                    .executeIfApplicable(state, process, ctx);
            if (state.getPendingRecovery() != null) {
                // Content judge flagged a gap — recovery
                // hint already attached.
                return;
            }
            if (!contentRan) {
                appendIteration(state,
                        expectedPaths.size() + " expected artifacts",
                        "passed — structural ok, no user criteria for "
                                + "content judge",
                        PhaseIteration.IterationOutcome.PASSED);
            }
            log.info("Slartibartfast id='{}' EXECUTION_VALIDATING passed",
                    process.getId());
            return;
        }

        String hint = buildRecoveryHint(missing, tooSmall, present);
        state.setPendingRecovery(RecoveryRequest.builder()
                .fromPhase(ArchitectStatus.EXECUTION_VALIDATING)
                .toPhase(ArchitectStatus.PROPOSING)
                .reason(RULE_ARTIFACTS_PRESENT)
                .hint(hint)
                .build());

        appendIteration(state,
                expectedPaths.size() + " expected artifacts",
                "FAILED — missing=" + missing.size()
                        + " tooSmall=" + tooSmall.size()
                        + "; rollback to PROPOSING",
                PhaseIteration.IterationOutcome.REQUESTED_RECOVERY);

        log.info("Slartibartfast id='{}' EXECUTION_VALIDATING failed — "
                        + "missing={} tooSmall={}, requesting PROPOSING re-run",
                process.getId(), missing, tooSmall);
    }

    private static String buildCheckMessage(
            Set<String> expected, List<String> missing, List<String> tooSmall) {
        StringBuilder sb = new StringBuilder();
        sb.append(expected.size()).append(" paths expected from subgoals; ");
        if (missing.isEmpty() && tooSmall.isEmpty()) {
            sb.append("all present.");
        } else {
            if (!missing.isEmpty()) {
                sb.append(missing.size()).append(" missing: ").append(missing);
            }
            if (!tooSmall.isEmpty()) {
                if (!missing.isEmpty()) sb.append("; ");
                sb.append(tooSmall.size()).append(" under-sized: ").append(tooSmall);
            }
        }
        return sb.toString();
    }

    private static String buildRecoveryHint(
            List<String> missing, List<String> tooSmall,
            List<String> present) {
        StringBuilder sb = new StringBuilder();
        sb.append("The previously generated recipe ran to completion "
                + "but did not produce all expected artifacts.\n\n");
        // ✓-list first so the LLM keeps the working phases.
        if (!present.isEmpty()) {
            sb.append("✓ ALREADY PRODUCED — keep the recipe phases "
                    + "that wrote these:\n");
            for (String p : present) {
                sb.append("- ").append(p).append("\n");
            }
            sb.append("\n");
        }
        sb.append("✗ MISSING OR TOO SMALL — fix only these:\n");
        if (!missing.isEmpty()) {
            sb.append("- Missing entirely (no document at path): ")
                    .append(missing).append("\n");
        }
        if (!tooSmall.isEmpty()) {
            sb.append("- Below minimum content (")
                    .append(MIN_ARTIFACT_CHARS)
                    .append(" chars): ").append(tooSmall).append("\n");
        }
        sb.append("\nPOSSIBLE OPTIONS to fix this — choose one or "
                + "combine:\n");
        sb.append("- Add a dedicated phase that uses worker 'ford' "
                + "with a workerInput that explicitly says "
                + "\"doc_create kind=text path=<MISSING-PATH> content=...\"; "
                + "name the phase so its purpose is obvious "
                + "(e.g. 'write-sources', 'write-introduction').\n");
        sb.append("- Extend an existing phase's workerInput to also "
                + "produce the missing path — keep the phase focused "
                + "but make the file output explicit.\n");
        sb.append("- Split a phase that was supposed to produce multiple "
                + "files into separate phases, one per file. "
                + "Concentrated single-file workerInputs land more "
                + "reliable output than catch-all instructions.\n");
        sb.append("- For long-form content (chapters, full essays), "
                + "use worker 'marvin-worker' instead of 'ford' — "
                + "Marvin decomposes into sub-tasks and is more "
                + "robust for outputs over 2000 chars.\n");
        sb.append("- Ensure the final consolidation/assembly phase "
                + "(if planned) actually writes essay/final-essay.md "
                + "(or whatever the kit's OUTPUT manual specifies) "
                + "via doc_create; it's the most common phase "
                + "to silently skip.\n");
        sb.append("\nKEEP what worked: phases that DID produce their "
                + "artifacts. Only rewrite the broken parts.");
        return sb.toString();
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.EXECUTION_VALIDATING)
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
}
