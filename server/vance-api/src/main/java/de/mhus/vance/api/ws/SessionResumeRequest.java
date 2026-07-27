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
 *
 * <p>{@link #isTakeover()} controls what happens when the session is already
 * held by a <em>live</em> sibling connection of the same user. Default
 * ({@code false}): the server refuses with a {@code 409}
 * {@link ErrorData#REASON_SESSION_BOUND_ELSEWHERE} instead of silently kicking
 * the sibling, so the client can ask the user first. {@code true}: the server
 * takes the bind over, closing the sibling connection. Daemons / service
 * accounts set this unconditionally; interactive clients only after an explicit
 * user confirmation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionResumeRequest {

    private String sessionId;

    private boolean takeover;
}
