package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The tenant-wide tool-templates catalog. Persisted at
 * {@code _tenant/config/tool-templates.yaml}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplateCatalogDto {

    /** Schema version (currently 1). */
    private int version;

    /** The entries — order is the order shown in the Web-UI wizard. */
    @Builder.Default
    private List<ToolTemplateCatalogEntry> templates = new ArrayList<>();
}
