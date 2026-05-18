package de.mhus.vance.brain.deepthought.phases;

import de.mhus.vance.api.deepthought.DeepThoughtState;
import de.mhus.vance.api.deepthought.DeepThoughtState.ValidationError;
import de.mhus.vance.api.deepthought.DeepThoughtStatus;
import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * VALIDATING — parse-only check via {@link JsValidationService}. On
 * success → DONE (or EXECUTING when {@code executeOnDone}). On
 * failure → increment {@code recoveryCount}; back to DRAFTING with
 * the errors copied into the state, or FAILED once
 * {@code recoveryCount >= maxRecoveries}.
 */
@Component("deepThoughtValidatingPhase")
@RequiredArgsConstructor
@Slf4j
public class ValidatingPhase {

    private final JsValidationService jsValidationService;

    public DeepThoughtStatus execute(
            DeepThoughtState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String code = state.getGeneratedCode();
        JsValidationService.JsValidationResult result =
                jsValidationService.validate(code, "draft.js");
        if (result.ok()) {
            state.getValidationErrors().clear();
            log.info("DeepThought.runValidating id='{}' OK after {} recovery attempt(s)",
                    process.getId(), state.getRecoveryCount());
            return state.isExecuteOnDone()
                    ? DeepThoughtStatus.EXECUTING
                    : DeepThoughtStatus.DONE;
        }

        List<ValidationError> errors = new ArrayList<>();
        for (JsValidationService.JsValidationError e : result.errors()) {
            errors.add(ValidationError.builder()
                    .sourceName(e.sourceName())
                    .line(e.line())
                    .column(e.column())
                    .message(e.message())
                    .build());
        }
        state.setValidationErrors(errors);
        state.setRecoveryCount(state.getRecoveryCount() + 1);
        log.info("DeepThought.runValidating id='{}' FAIL — attempt {}/{}, errors: {}",
                process.getId(), state.getRecoveryCount(),
                state.getMaxRecoveries(),
                errors.isEmpty() ? "?" : errors.get(0).getMessage());

        if (state.getRecoveryCount() >= state.getMaxRecoveries()) {
            state.setFailureReason("Exceeded maxRecoveries ("
                    + state.getMaxRecoveries() + ") — last error: "
                    + (errors.isEmpty() ? "(none)" : errors.get(0).getMessage()));
            return DeepThoughtStatus.FAILED;
        }
        return DeepThoughtStatus.DRAFTING;
    }
}
