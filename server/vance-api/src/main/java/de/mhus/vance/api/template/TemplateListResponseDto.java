package de.mhus.vance.api.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for {@code GET /brain/{tenant}/templates} — aggregated
 * across all cascade layers (project, tenant, bundled) with dedup by
 * name (innermost wins).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("template")
public class TemplateListResponseDto {

    @Builder.Default
    private List<TemplateSummaryDto> templates = new ArrayList<>();
}
