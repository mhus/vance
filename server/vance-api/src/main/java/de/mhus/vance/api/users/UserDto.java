package de.mhus.vance.api.users;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read view of a user. The password hash never leaves the server —
 * this DTO carries only metadata. {@link #status} is one of
 * {@code ACTIVE} / {@code DISABLED} / {@code PENDING}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("users")
public class UserDto {

    private String name;

    private @Nullable String title;

    private @Nullable String email;

    private String status;

    private @Nullable Instant createdAt;
}
