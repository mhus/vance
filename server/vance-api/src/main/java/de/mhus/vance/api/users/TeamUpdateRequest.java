package de.mhus.vance.api.users;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/admin/teams/{name}}. {@code null}
 * fields mean "leave as is". {@link #members} replaces the list
 * wholesale when non-null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("users")
public class TeamUpdateRequest {

    private @Nullable String title;

    private @Nullable Boolean enabled;

    private @Nullable List<String> members;
}
