package de.mhus.vance.api.insights;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Every catalog field of one model, plus its provider sidecar, each with its source layer. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("insights")
public class ModelDetailInsightsDto {

    private CatalogModelInsightsDto model;

    private List<ModelFieldInsightsDto> fields;

    /** Fields of the instance's {@code _provider.yaml}; empty when there is none. */
    private List<ModelFieldInsightsDto> providerFields;
}
