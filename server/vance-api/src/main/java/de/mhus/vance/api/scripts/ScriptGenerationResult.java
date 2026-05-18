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
 * Carries the final state of a Script-Cortex generation; {@code code}
 * is the generated JavaScript when {@code status=DONE}.
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

    /** Mirrors Deep-Thought-state {@code reason} (DONE/FAILED). */
    private @Nullable String reason;

    private @Nullable String code;

    /** Reviewer/Validator notes — surfaced as a small banner above the
     *  apply-button so the user sees what Deep-Thought thought of its
     *  own output. */
    private @Nullable String reviewerNotes;

    private @Nullable String planSketch;

    private @Nullable String failureReason;
}
