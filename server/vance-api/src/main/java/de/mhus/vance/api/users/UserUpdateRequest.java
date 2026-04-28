package de.mhus.vance.api.users;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/admin/users/{name}}. {@code null}
 * fields mean "leave as is". Password changes go through a separate
 * endpoint so the plaintext doesn't leak into update logs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("users")
public class UserUpdateRequest {

    private @Nullable String title;

    private @Nullable String email;

    /** {@code ACTIVE} / {@code DISABLED} / {@code PENDING}. */
    private @Nullable String status;
}
