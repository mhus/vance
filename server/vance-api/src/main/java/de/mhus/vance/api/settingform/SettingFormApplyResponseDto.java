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
 * Response body for {@code POST /apply}, {@code /validate}, and
 * {@code /reset}. Holds the flat list of setting actions the brain
 * performed (or, for {@code /validate}, would have performed).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settingform")
public class SettingFormApplyResponseDto {

    @Builder.Default
    private List<AppliedSettingDto> applied = new ArrayList<>();
}
