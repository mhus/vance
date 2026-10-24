package de.mhus.vance.api.roles;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Answer of {@code GET /brain/{tenant}/me/roles}: the caller's own roles, for
 * the Web-UI to decide which surfaces to show. The server still enforces every
 * request — this is display information, not authority.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("roles")
public class MyRolesDto {

    private TenantRole tenantRole;

    /** The project asked about, if any. */
    private @Nullable String project;

    /**
     * The caller's own membership in {@link #project} — the stored role, not
     * what the tenant role implies. {@code null} when not a member or when no
     * project was asked about.
     */
    private @Nullable ProjectRole projectRole;
}
