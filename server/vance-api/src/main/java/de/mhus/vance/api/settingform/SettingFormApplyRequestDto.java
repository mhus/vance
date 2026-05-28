package de.mhus.vance.api.settingform;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body for {@code POST /brain/{tenant}/setting-forms/{name}/apply} and
 * the dry-run counterpart {@code /validate}. Carries the user-filled
 * form values keyed by field {@code name}.
 *
 * <p>Value-type convention mirrors
 * {@link de.mhus.vance.api.wizard.WizardRenderRequestDto}: scalar
 * fields as {@code String}, multi-selects as {@code List<String>},
 * repeats as {@code List<Map<String,Object>>}.
 *
 * <p>For {@code password}-typed fields, an <b>empty string</b> means
 * "do not modify" (see spec §6.4); the corresponding setting is left
 * untouched. To clear a password, use the Reset action or supply an
 * explicit {@code writeIf} guard on the field.
 *
 * <p>{@link #lang} is optional — used to localize render-context
 * variables in Pebble templates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settingform")
public class SettingFormApplyRequestDto {

    @Builder.Default
    private Map<String, Object> values = new HashMap<>();

    private @Nullable String lang;
}
