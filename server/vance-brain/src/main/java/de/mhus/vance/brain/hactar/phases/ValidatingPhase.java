package de.mhus.vance.brain.hactar.phases;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.hactar.HactarService;
import de.mhus.vance.brain.hactar.HactarService.ValidationRequest;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * VALIDATING — opt-in deep semantic validation via
 * {@code HactarService.deepValidate(...)}. Only entered when
 * {@code state.validateBeforeRun=true} — the LOADING phase
 * decides the next status based on that flag.
 *
 * <p>Deep validation runs a {@code LightLlmService} call with the
 * bundled {@code script-review} recipe; the LLM reviews the script
 * for semantic / logic / API-misuse issues. On reject → FAILED with
 * the LLM-reported issues stashed; on pass → EXECUTING.
 *
 * <p>This phase is opt-in because it costs an LLM round-trip
 * (~$0.001-0.005 per call) and is unnecessary for scripts that
 * have already proven themselves (scheduler-driven cron runs,
 * Slart-self-execute output that just passed Slart's own
 * VALIDATING loop).
 */
@Component("hactarValidatingPhase")
@RequiredArgsConstructor
@Slf4j
public class ValidatingPhase {

    private final HactarService hactarService;

    public HactarStatus execute(
            HactarState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String code = state.getScriptBody();
        if (code == null || code.isBlank()) {
            state.setFailureReason("VALIDATING entered with empty scriptBody — "
                    + "LOADING must run first");
            return HactarStatus.FAILED;
        }

        String sourceName = state.getScriptRef() == null
                ? "<inline>" : state.getScriptRef();
        Set<String> callerAllowed = LoadingPhase.scriptAllowedTools(process);

        HactarService.ValidationResult result;
        try {
            result = hactarService.deepValidate(new ValidationRequest(
                    code,
                    state.getLanguage() == null ? "js" : state.getLanguage(),
                    sourceName,
                    callerAllowed.isEmpty() ? null : callerAllowed,
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getId()));
        } catch (RuntimeException e) {
            state.setFailureReason("VALIDATING deepValidate threw: "
                    + e.getMessage());
            log.warn("Hactar.runValidating id='{}' HactarService.deepValidate "
                            + "threw: {}",
                    process.getId(), e.toString());
            return HactarStatus.FAILED;
        }

        // Merge LOADING-issues (already on state) with deep-validate
        // issues so a downstream consumer sees everything that was
        // flagged. dedupe via Set on message+code.
        java.util.List<HactarState.ValidationIssue> merged =
                new java.util.ArrayList<>(state.getValidationIssues());
        Set<String> seen = new LinkedHashSet<>();
        for (HactarState.ValidationIssue existing : merged) {
            seen.add(existing.getCode() + "::" + existing.getMessage());
        }
        for (HactarService.ValidationIssue issue : result.issues()) {
            String key = issue.code() + "::" + issue.message();
            if (seen.add(key)) {
                merged.add(HactarState.ValidationIssue.builder()
                        .severity(issue.severity() == null ? null : issue.severity().name())
                        .code(issue.code())
                        .message(issue.message())
                        .line(issue.line())
                        .column(issue.column())
                        .build());
            }
        }
        state.setValidationIssues(merged);

        if (!result.ok()) {
            StringBuilder reason = new StringBuilder("Script failed deep-validate (");
            reason.append(result.issues().size()).append(" issue(s)):");
            int shown = 0;
            for (HactarService.ValidationIssue issue : result.issues()) {
                if (issue.severity() != HactarService.Severity.ERROR) continue;
                reason.append("\n- [").append(issue.code()).append("] ")
                        .append(issue.message());
                if (++shown >= 5) break;
            }
            state.setFailureReason(reason.toString());
            return HactarStatus.FAILED;
        }

        log.info("Hactar.runValidating id='{}' deep-validate OK ({} issues, all non-error)",
                process.getId(), result.issues().size());
        return HactarStatus.EXECUTING;
    }
}
