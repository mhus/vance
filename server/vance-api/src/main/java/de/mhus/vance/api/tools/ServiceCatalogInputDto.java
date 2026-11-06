package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Something the person has to say when a trigger of the service is switched on. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ServiceCatalogInputDto {

    private String name;

    private String description;

    private boolean required;
}
