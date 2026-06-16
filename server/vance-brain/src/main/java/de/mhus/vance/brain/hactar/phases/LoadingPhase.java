package de.mhus.vance.brain.hactar.phases;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.hactar.HactarService;
import de.mhus.vance.brain.hactar.HactarService.ValidationRequest;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LOADING — fetches the script body from {@code state.scriptRef}
 * via the document cascade (project → {@code _vance} → bundled),
 * then runs {@code HactarService.validate(...)} for the minimal
 * Parse + Header + Tool-Allowlist gate. The latter is the
 * pre-flight check that catches bad headers and missing-tool
 * declarations <em>before</em> a single statement runs.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>Document missing or empty → FAILED with a clear reason.</li>
 *   <li>Minimal validation rejects → FAILED with the aggregated
 *       issues stashed on the state.</li>
 *   <li>Validation passes → VALIDATING (when
 *       {@code state.validateBeforeRun=true}) or directly to
 *       EXECUTING.</li>
 * </ul>
 *
 * <p>No recovery — Hactar v2 doesn't author. If the script is
 * broken, the caller's next step is to spawn Slart with
 * {@code mode=UPDATE + existingScriptRef + failureReason}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoadingPhase {

    /** Engine-param key — caller's allow-set the script tools are
     *  intersected against. Inherited from the spawning recipe /
     *  process. When unset, validate skips the intersect check
     *  and only verifies parse + header well-formedness. */
    public static final String SCRIPT_ALLOWED_TOOLS_KEY = "scriptAllowedTools";

    private final DocumentService documentService;
    private final HactarService hactarService;

    public HactarStatus execute(
            HactarState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String path = state.getScriptRef();
        if (path == null || path.isBlank()) {
            state.setFailureReason(
                    "LOADING entered without a scriptRef — "
                            + "buildInitialState should have caught this");
            return HactarStatus.FAILED;
        }

        Optional<LookupResult> hit = documentService.lookupCascade(
                process.getTenantId(), process.getProjectId(), path);
        if (hit.isEmpty()) {
            state.setFailureReason("Script document not found: " + path);
            log.warn("Hactar.runLoading id='{}' document not found at '{}'",
                    process.getId(), path);
            return HactarStatus.FAILED;
        }

        String content = hit.get().content();
        if (content == null || content.isBlank()) {
            state.setFailureReason("Script document is empty: " + path);
            log.warn("Hactar.runLoading id='{}' document at '{}' is empty",
                    process.getId(), path);
            return HactarStatus.FAILED;
        }

        state.setScriptBody(content);

        // Minimal validation gate — parse + header + caller-tool-
        // intersect. Cheap (~ms), always runs.
        Set<String> callerAllowed = scriptAllowedTools(process);
        HactarService.ValidationResult result;
        try {
            result = hactarService.validate(new ValidationRequest(
                    content,
                    state.getLanguage() == null ? "js" : state.getLanguage(),
                    path,
                    callerAllowed.isEmpty() ? null : callerAllowed,
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getId()));
        } catch (RuntimeException e) {
            state.setFailureReason("LOADING validate threw: " + e.getMessage());
            log.warn("Hactar.runLoading id='{}' HactarService.validate threw: {}",
                    process.getId(), e.toString());
            return HactarStatus.FAILED;
        }

        // Translate HactarService issues into the API DTO so callers
        // (parent, Cortex run-panel) see them without depending on
        // the brain-internal record.
        state.setValidationIssues(result.issues().stream()
                .map(LoadingPhase::toApi)
                .toList());

        if (!result.ok()) {
            StringBuilder reason = new StringBuilder("Script failed pre-flight validation (");
            reason.append(result.issues().size()).append(" issue(s)):");
            int shown = 0;
            for (HactarService.ValidationIssue issue : result.issues()) {
                if (issue.severity() != HactarService.Severity.ERROR) continue;
                reason.append("\n- [").append(issue.code()).append("] ");
                if (issue.line() != null) {
                    reason.append("line ").append(issue.line());
                    if (issue.column() != null) reason.append(":").append(issue.column());
                    reason.append(" — ");
                }
                reason.append(issue.message());
                if (++shown >= 5) break;
            }
            state.setFailureReason(reason.toString());
            return HactarStatus.FAILED;
        }

        log.info("Hactar.runLoading id='{}' loaded {} chars from '{}' "
                        + "(source={}, validateBeforeRun={})",
                process.getId(), content.length(), path,
                hit.get().source().name().toLowerCase(),
                state.isValidateBeforeRun());

        return state.isValidateBeforeRun()
                ? HactarStatus.VALIDATING
                : HactarStatus.EXECUTING;
    }

    // ──────────────────── Helpers ────────────────────

    @SuppressWarnings("unchecked")
    static Set<String> scriptAllowedTools(ThinkProcessDocument process) {
        if (process.getEngineParams() == null) return Set.of();
        Object raw = process.getEngineParams().get(SCRIPT_ALLOWED_TOOLS_KEY);
        if (raw instanceof List<?> list) {
            Set<String> out = new LinkedHashSet<>();
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) out.add(s);
            }
            return out;
        }
        return Set.of();
    }

    private static HactarState.ValidationIssue toApi(
            HactarService.ValidationIssue issue) {
        return HactarState.ValidationIssue.builder()
                .severity(issue.severity() == null ? null : issue.severity().name())
                .code(issue.code())
                .message(issue.message())
                .line(issue.line())
                .column(issue.column())
                .build();
    }
}
