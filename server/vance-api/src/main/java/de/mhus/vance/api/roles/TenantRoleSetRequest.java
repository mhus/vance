package de.mhus.vance.api.roles;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /brain/{tenant}/admin/roles/tenant/{user}}. Setting
 * {@link TenantRole#USER} removes the stored assignment — every member holds
 * USER implicitly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("roles")
public class TenantRoleSetRequest {

    @NotNull
    private TenantRole role;
}
