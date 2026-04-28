package de.mhus.vance.api.users;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/admin/users}. {@link #password}
 * is plaintext — it is hashed on the server before storage and never
 * logged. {@code null}/blank password creates a passwordless account
 * that cannot log in until {@code PUT /admin/users/{name}/password}
 * sets one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("users")
public class UserCreateRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9_.-]*$",
            message = "must be lower-case alphanumerics with optional '.', '-' or '_'")
    private String name;

    private @Nullable String title;

    private @Nullable String email;

    private @Nullable String password;
}
