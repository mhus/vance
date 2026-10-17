package de.mhus.vance.api.insights;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One top-level field of a merged catalog document and the layer it came
 * from. The catalog merges per top-level field, so a nested value
 * ({@code pricing}) always comes whole from one layer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("insights")
public class ModelFieldInsightsDto {

    private String name;

    /** Display rendering of the YAML value. */
    private String value;

    private ModelCatalogLayer layer;
}
