package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for a {@link MessageType#SESSION_RESUME} request.
 *
 * <p>Allowed only on a connection that has not yet bound a session. The caller
 * must own the target session (same tenant + user). On success the connection
 * is bound and {@link SessionResumeResponse} is returned.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionResumeRequest {

    private String sessionId;
}
