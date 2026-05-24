package de.mhus.vance.api.wizard;

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
 * Body for {@code POST /brain/{tenant}/wizards/{name}/render}. Carries
 * the user-filled form values keyed by field {@code name}.
 *
 * <p>Value types follow the {@code FormValue} convention:
 * <ul>
 *   <li>scalar fields (string / textarea / password / integer / boolean / select):
 *       {@code String} (booleans encoded as {@code "true"}/{@code "false"},
 *       integers as numeric strings)</li>
 *   <li>{@code multi_select}: {@code List<String>}</li>
 *   <li>{@code repeat}: {@code List<Map<String, Object>>} — each map
 *       follows the same convention recursively, but nested {@code repeat}
 *       is not supported in v1</li>
 * </ul>
 *
 * <p>Jackson decodes the heterogeneous values into {@code Object};
 * the brain-side {@code FormValidator} type-checks them against the
 * resolved wizard's field schema before rendering.
 *
 * <p>{@link #lang} is an optional override — the brain falls back to
 * the tenant default language when omitted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("wizard")
public class WizardRenderRequestDto {

    @Builder.Default
    private Map<String, Object> values = new HashMap<>();

    private @Nullable String lang;
}
