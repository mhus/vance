package de.mhus.vance.api.users;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/admin/teams}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("users")
public class TeamCreateRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
            message = "must be lower-case alphanumerics with optional '-' or '_'")
    private String name;

    private @Nullable String title;

    @Builder.Default
    private List<String> members = new ArrayList<>();
}
