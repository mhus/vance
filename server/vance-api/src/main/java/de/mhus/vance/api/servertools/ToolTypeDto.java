package de.mhus.vance.api.servertools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Discovery DTO for one registered tool factory. The UI uses
 * {@link #parametersSchema} to render an appropriate editor for the
 * configuration of the chosen type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("server-tools")
public class ToolTypeDto {

    /** Stable identifier — the value stored in {@code ServerToolDto.type}. */
    private String typeId;

    /** JSON-Schema (object-shaped) for the {@code parameters} map. */
    @Builder.Default
    private Map<String, Object> parametersSchema = new LinkedHashMap<>();
}
