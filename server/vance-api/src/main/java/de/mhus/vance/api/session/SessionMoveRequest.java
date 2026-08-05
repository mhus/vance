package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for the session-move call: the target project the session should be
 * moved into. Same-tenant only — the tenant comes from the request path.
 *
 * <p>See {@code planning/session-move.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionMoveRequest {

    /** Target project ({@code ProjectDocument.name}) within the same tenant. */
    @NotBlank
    private String targetProjectId = "";
}
