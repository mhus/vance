package de.mhus.vance.api.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /brain/{tenant}/profile/password} — the caller
 * changes their own password. Both fields are plaintext, hashed /
 * verified server-side and never logged.
 *
 * <p>{@link #currentPassword} authenticates the change (must match the
 * stored hash); {@link #newPassword} is validated against the tenant
 * password policy before it is stored. The server is the policy
 * authority — this DTO only enforces non-blankness.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("profile")
public class ProfilePasswordRequest {

    @NotBlank
    private String currentPassword;

    @NotBlank
    private String newPassword;
}
