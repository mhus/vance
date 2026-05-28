package de.mhus.vance.api.settingform;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for {@code GET /brain/{tenant}/setting-forms} —
 * aggregated across all cascade layers with dedup by name (innermost
 * wins) and filtered by the form's {@code availableIn} patterns
 * against the requested project context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settingform")
public class SettingFormListResponseDto {

    @Builder.Default
    private List<SettingFormSummaryDto> forms = new ArrayList<>();
}
