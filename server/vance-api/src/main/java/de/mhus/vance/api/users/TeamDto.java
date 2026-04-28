package de.mhus.vance.api.users;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read view of a team. {@link #members} are usernames
 * ({@code UserDocument.name} values) inside the same tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("users")
public class TeamDto {

    private String name;

    private @Nullable String title;

    @Builder.Default
    private List<String> members = new ArrayList<>();

    private boolean enabled;

    private @Nullable Instant createdAt;
}
