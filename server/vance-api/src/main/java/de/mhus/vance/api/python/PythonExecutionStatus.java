package de.mhus.vance.api.python;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Returned by {@code GET /python/executions/{executionId}}. Snapshot
 * of one Python job — re-rendered on every call, truncated past the
 * server's inline-output cap (caller can stream from
 * {@code stdoutPath} / {@code stderrPath} on the file-system if it
 * needs the full body, though that's brain-internal).
 *
 * <p>{@code state} normalises the underlying {@code ExecJob.Status}
 * enum to lowercase keywords matching the Cortex runner's
 * {@code RunState}: {@code running}, {@code finished},
 * {@code failed}, {@code cancelled}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("python")
public class PythonExecutionStatus {

    private String executionId;
    private String state;
    private @Nullable Integer exitCode;
    private @Nullable Long durationMs;
    @Builder.Default
    private String stdout = "";
    @Builder.Default
    private String stderr = "";
    /** Set when the renderer truncated stdout / stderr past the
     *  inline cap. UI can show a "more in log file" hint. */
    @Builder.Default
    private boolean truncated = false;
    private @Nullable String errorMessage;
}
