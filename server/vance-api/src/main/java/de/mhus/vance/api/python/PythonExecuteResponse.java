package de.mhus.vance.api.python;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Returned by {@code POST /python/execute}. The execution is async;
 * the caller polls {@code GET /python/executions/{executionId}} for
 * state + stdout/stderr until terminal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("python")
public class PythonExecuteResponse {

    private String executionId;
}
