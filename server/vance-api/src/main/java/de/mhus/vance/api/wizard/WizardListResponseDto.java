package de.mhus.vance.api.wizard;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for {@code GET /brain/{tenant}/wizards} — aggregated
 * across all cascade layers (project, user, tenant, bundled) with
 * dedup by name (innermost wins).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("wizard")
public class WizardListResponseDto {

    @Builder.Default
    private List<WizardSummaryDto> wizards = new ArrayList<>();
}
