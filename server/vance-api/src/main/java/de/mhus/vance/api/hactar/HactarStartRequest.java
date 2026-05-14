package de.mhus.vance.api.hactar;

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
 * Request body for {@code POST /brain/{tenant}/project/{project}/workflows/{name}/start}
 * and the {@code workflow_start} agent tool. See plan §8.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("hactar")
public class HactarStartRequest {

    /** Caller-supplied parameters, validated against {@code parameters:} block in the workflow YAML. */
    private @Nullable Map<String, Object> params;

    /** Audit hint — user id, scheduler key, or hook origin. Logged in {@code StartRecord}. */
    private @Nullable @NotBlank String startedBy;
}
