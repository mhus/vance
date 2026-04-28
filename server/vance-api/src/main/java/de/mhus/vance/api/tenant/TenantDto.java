package de.mhus.vance.api.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read view of a tenant. {@code name} is immutable; everything else is
 * editable through {@link TenantUpdateRequest}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tenant")
public class TenantDto {

    private String name;

    private @Nullable String title;

    private boolean enabled;

    private @Nullable Instant createdAt;
}
