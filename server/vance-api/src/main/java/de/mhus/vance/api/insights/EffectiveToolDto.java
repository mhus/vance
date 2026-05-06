package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One effective tool as the engines see it for the chosen project,
 * with its cascade origin attribution. Cascade priority is
 * {@code PROJECT → VANCE → BUILTIN}; only the innermost-winning
 * entry shows up in the list.
 *
 * <p>{@link #disabledByInnerLayer} is purely diagnostic: when a
 * project layer carries an {@code enabled=false} document it removes
 * the inner-layer tool from the engine's effective set; the entry
 * doesn't appear at all in normal mode. With the insights flag set
 * the controller can surface the suppression so users understand
 * "where did the bundled tool go".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class EffectiveToolDto {

    private String name;

    private String description;

    /** Advertised to the LLM on every turn vs. discoverable only via {@code find_tools}. */
    private boolean primary;

    /** {@code PROJECT} | {@code VANCE} | {@code BUILTIN}. */
    private String source;

    /** Selector tags from {@link de.mhus.vance.api.tools.ToolSpec#labels} or descriptor metadata. */
    private List<String> labels;

    /**
     * For configured tools (PROJECT / VANCE), the {@code type} from the
     * {@code ServerToolDocument} (e.g. {@code "rest"}, {@code "javascript"}).
     * {@code null} for built-in beans.
     */
    private @Nullable String type;

    /** Diagnostic — see class doc. */
    private boolean disabledByInnerLayer;
}
