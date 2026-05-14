package de.mhus.vance.api.hactar;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
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
 * {@link HactarWorkflowResolver} cascade in plan §12.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("hactar")
public class HactarWorkflowDto {

    /** Workflow name — derived from the document path, not stored in the YAML body. */
    private String name;

    /** Verbatim YAML body. */
    private String yaml;

    /** Which cascade tier provides this workflow. */
    private HactarWorkflowSource source;

    // ─── Parsed convenience fields (mirror of YAML for the UI) ───

    private @Nullable String description;
    private @Nullable String version;

    /** Initial state when a run is started. */
    private @Nullable String start;

    private @Nullable List<String> allowedTools;
    private @Nullable List<String> tags;
}
