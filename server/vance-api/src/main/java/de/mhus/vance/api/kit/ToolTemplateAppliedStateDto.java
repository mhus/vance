package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Audit blob written after each successful tool-template apply, lives at
 * {@code <project>/_vance/tool-templates/&lt;template&gt;.applied.yaml}.
 * Surfaced by {@code GET /admin/tool-templates/{name}/applied} so the
 * Web-UI Wizard can pre-fill the form on re-open.
 *
 * <p><strong>Never contains PASSWORD-typed input values</strong> — those
 * live exclusively in the encrypted settings collection. The Web-UI
 * keeps password fields empty on re-open.
 *
 * <p>Re-apply <strong>overwrites</strong> this document — there is no
 * history. Audit trails belong in the event-log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplateAppliedStateDto {

    /** Template name (== catalog key, == {@code <name>} in the path). */
    private String template;

    /** ISO-8601 UTC timestamp of the most recent apply. */
    private String appliedAt;

    /** Subject-id of the actor that triggered the apply. */
    private @Nullable String appliedBy;

    /** Source-repo commit-sha at the time of apply (when known). */
    private @Nullable String sourceCommit;

    /**
     * Inputs as submitted at apply-time, minus PASSWORDs. Multi-select
     * values are decoded to {@code List<String>}; integers to
     * {@code Long}; booleans to {@code Boolean}; everything else stays a
     * {@code String}. JSON-marshalled values therefore reflect the
     * underlying type instead of forcing every value into a string.
     */
    @Builder.Default
    private Map<String, Object> inputs = new LinkedHashMap<>();

    /**
     * Convenience mirror of the active feature selection — pulled from
     * the first multi-select input. Empty when the template has no
     * multi-select input.
     */
    @Builder.Default
    private List<String> features = new ArrayList<>();

    /**
     * Derived variables computed at apply-time. Keyed by derived name,
     * value is the list of strings. Useful for diagnosing "why does the
     * OAuth provider have these scopes?" without recomputing.
     *
     * <p>Typed as {@code Map<String, Object>} (not the structurally cleaner
     * {@code Map<String, List<String>>}) because the TypeScript generator
     * does not flatten nested parameterised types inside Map values.
     * Jackson still serialises the list values to JSON arrays at runtime.
     */
    @Builder.Default
    private Map<String, Object> derived = new LinkedHashMap<>();
}
