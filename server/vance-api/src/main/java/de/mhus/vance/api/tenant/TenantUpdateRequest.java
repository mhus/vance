package de.mhus.vance.api.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/admin/tenant}. {@code name} is not part
 * of this contract — it is taken from the path and never modified.
 *
 * <p>Fields are nullable so callers can do partial updates: a {@code null}
 * field means "leave as is".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tenant")
public class TenantUpdateRequest {

    private @Nullable String title;

    private @Nullable Boolean enabled;
}
