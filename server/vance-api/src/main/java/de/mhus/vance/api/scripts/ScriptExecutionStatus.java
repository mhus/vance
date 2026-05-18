package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Returned by {@code GET /scripts/executions/{executionId}}. Carries a
 * snapshot of the current state plus the captured log buffer (oldest
 * lines truncated past 10 000 entries).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptExecutionStatus {

    /** {@code running}, {@code finished}, {@code failed}, {@code cancelled}. */
    private String state;

    private String executionId;

    private long startedAtMs;

    private @Nullable Long endedAtMs;

    private @Nullable Long durationMs;

    /** Mapped return value when {@code state=finished}; {@code null}
     *  otherwise. Primitives stay primitives, JS objects are
     *  {@code Map}, JS arrays are {@code List}. */
    private @Nullable Object resultValue;

    private @Nullable String errorMessage;

    @Builder.Default
    private List<String> logBuffer = new ArrayList<>();
}
