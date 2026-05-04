package de.mhus.vance.api.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/profile} — patch the caller's
 * own profile. Only mutable identity fields are accepted; status and
 * password are admin-territory and live elsewhere.
 *
 * <p>{@code null} fields mean "leave as is". An explicit empty string
 * clears the value (e.g. {@code email=""}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("profile")
public class ProfileUpdateRequest {

    private @Nullable String title;

    private @Nullable String email;
}
