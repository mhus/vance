package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One dimension a provider instance can be filtered by.
 *
 * <p>Carries its values because the picker cannot be drawn without them: an
 * id like {@code m49:142} is not a label, and the reader has no table to look
 * it up in. For a {@code lazyChildren} facet only the top level travels and
 * the rest is fetched a level at a time — the flag is on the wire so an empty
 * child list can be told apart from one that was never asked for.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class FacetInsightsDto {

    /** Facet key, e.g. {@code origin-place}. */
    private String key;

    /** Display label as the source wrote it — single-language, never translated. */
    private String label;

    /** Whether values form a containment tree; a UI hint, nothing more. */
    private boolean hierarchical;

    /** Whether deeper levels have to be fetched rather than being in {@link #values}. */
    private boolean lazyChildren;

    /** The values that travelled with the declaration. */
    private List<FacetValueInsightsDto> values;
}
