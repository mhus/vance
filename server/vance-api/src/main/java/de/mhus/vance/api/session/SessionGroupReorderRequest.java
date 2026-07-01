package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /brain/{tenant}/session-groups/order}.
 *
 * <p>The full, ordered list of group {@code name}s for the caller's
 * {@code (tenant, project, user)} scope. The service re-assigns
 * {@code sortIndex} from the list position.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionGroupReorderRequest {

    @NotEmpty
    @Builder.Default
    private List<String> orderedNames = new ArrayList<>();
}
