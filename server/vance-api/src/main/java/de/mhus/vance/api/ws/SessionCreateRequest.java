package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for a {@link MessageType#SESSION_CREATE} request.
 *
 * <p>Allowed only on a connection that has not yet bound a session. The server
 * creates a new {@code SessionDocument} scoped to {@code projectId} inside the
 * caller's tenant, atomically binds it to this connection, and replies with
 * {@link SessionCreateResponse}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionCreateRequest {

    /** {@code ProjectDocument.name} the new session should be attached to. */
    private String projectId;
}
