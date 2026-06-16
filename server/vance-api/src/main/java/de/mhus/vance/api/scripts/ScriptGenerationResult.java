package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Returned by {@code GET /scripts/generations/{thinkProcessId}/result}.
 * Carries the final state of a Cortex script-generation run; {@code code}
 * is the generated JavaScript when the Slart run reached
 * {@code status=DONE}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptGenerationResult {

    private String thinkProcessId;

    /** Mirrors {@link de.mhus.vance.api.thinkprocess.ThinkProcessStatus}. */
    private String status;

    /** Mirrors {@link de.mhus.vance.api.slartibartfast.ArchitectStatus}
     *  (DONE / FAILED / etc.). */
    private @Nullable String reason;

    /** The generated JavaScript body. Present once the Slart run
     *  reached PERSISTING — even pre-DONE runs (still in
     *  EXECUTION_VALIDATING) carry the draft so Cortex can preview. */
    private @Nullable String code;

    private @Nullable String failureReason;
}
