package de.mhus.vance.api.magrathea;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/project/{project}/workflows/{name}/start}
 * and the {@code workflow_start} agent tool. See plan §8.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("magrathea")
public class MagratheaStartRequest {

    /** Caller-supplied parameters, validated against {@code parameters:} block in the workflow YAML. */
    private @Nullable Map<String, Object> params;

    /**
     * Start in debug mode: the run halts before each state and waits to be
     * stepped from the run view. Real calls — debug is not a dry run.
     * Absent means a normal run.
     */
    private @Nullable Boolean debug;

    /**
     * States to halt before. The run then goes freely between them and stands in
     * front of each one; independent of {@link #debug}, which halts before every
     * state. Names must exist in the workflow.
     */
    private @Nullable List<String> breakpoints;
}
