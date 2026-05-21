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
 * Workflow definition for the editor — both the parsed identity fields
 * and the raw YAML body. The YAML is the source of truth
 * (round-trips verbatim including comments); the parsed fields are a
 * convenience for the UI form.
 *
 * <p>See plan §3.1 and §5 for the YAML schema, and the
 * {@link MagratheaWorkflowResolver} cascade in plan §12.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("magrathea")
public class MagratheaWorkflowDto {

    /** Workflow name — derived from the document path, not stored in the YAML body. */
    private String name;

    /** Verbatim YAML body. */
    private String yaml;

    /** Which cascade tier provides this workflow. */
    private MagratheaWorkflowSource source;

    // ─── Parsed convenience fields (mirror of YAML for the UI) ───

    private @Nullable String description;
    private @Nullable String version;

    /** Initial state when a run is started. */
    private @Nullable String start;

    /**
     * Parameter schema for manual triggers. Keys are the parameter
     * names from the YAML's {@code parameters:} block; values carry
     * type / required / default-value. {@code null} when the workflow
     * declares no parameters.
     */
    private @Nullable Map<String, MagratheaParameterDto> parameters;

    private @Nullable List<String> allowedTools;
    private @Nullable List<String> tags;
}
