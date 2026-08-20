package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One selectable value of a {@link FacetInsightsDto}; {@code parentId} null is
 * a root.
 *
 * <p>Top-level rather than nested inside the facet it belongs to: the
 * TypeScript generator writes one file per annotated class and imports by
 * simple name, so a nested type ends up referenced from a file that never
 * imports it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class FacetValueInsightsDto {

    private String id;

    private String label;

    private @Nullable String parentId;
}
