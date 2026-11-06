package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** A tool of a service worth pointing at. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ServiceCatalogHighlightDto {

    /** The full tool name, as it is listed. */
    private String tool;

    private String description;

    /** An example of the parameters, for a form to start from. */
    private @Nullable Map<String, Object> params;
}
