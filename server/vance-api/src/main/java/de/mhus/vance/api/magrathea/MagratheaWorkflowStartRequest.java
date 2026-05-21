package de.mhus.vance.api.magrathea;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * WS payload for {@code MessageType.WORKFLOW_START}. The workflow
 * name is part of the body (not the URL like REST) and is resolved
 * against the bound session's project cascade.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("magrathea")
public class MagratheaWorkflowStartRequest {

    /** Workflow definition name — resolved in the bound session's project. */
    @NotBlank
    private String name;

    private @Nullable Map<String, Object> params;

    /** Audit hint; defaults to the WS session's bound user. */
    private @Nullable String startedBy;
}
