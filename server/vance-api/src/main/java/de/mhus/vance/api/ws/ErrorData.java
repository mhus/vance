package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@link MessageType#ERROR} message — generic error response.
 *
 * {@link #getErrorCode()} follows HTTP semantics (400, 401, 403, 404, 409, 500).
 * {@link #getErrorMessage()} is a log-targeted text; clients should localize their
 * own user-facing copy based on the numeric code.
 *
 * <p>{@link #getReason()} is an optional machine-readable discriminator for
 * cases where the numeric code alone is ambiguous — the {@code 409} conflict
 * code in particular covers several distinct situations (pod redirect, private
 * session held by another user, session live in a sibling connection of the
 * same user). Clients branch on {@code reason} to pick the right UX. See the
 * {@code REASON_*} constants for the defined vocabulary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ErrorData {

    /**
     * {@code reason} value on a {@code 409} when the requested session is
     * currently held by a <em>live</em> sibling connection of the same user.
     * The client should offer an explicit "take over here?" confirmation and
     * only then re-issue the resume with {@code takeover = true}. Never auto-
     * escalate — that is exactly what produces the connect/kick ping-pong
     * between two windows of the same user.
     */
    public static final String REASON_SESSION_BOUND_ELSEWHERE = "session_bound_elsewhere";

    private int errorCode;

    private @Nullable String errorMessage;

    private @Nullable String reason;
}
