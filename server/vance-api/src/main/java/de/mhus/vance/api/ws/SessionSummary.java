package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Condensed view of a session for list responses.
 *
 * <p>{@code bound} reflects whether any connection currently owns the session
 * lock; a client can resume only sessions that are not bound.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionSummary {

    private String sessionId;

    private String projectId;

    private String status;

    /** Epoch millis. */
    private long createdAt;

    /** Epoch millis. */
    private long lastActivityAt;

    /** {@code true} while another connection still holds the lock. */
    private boolean bound;

    /** Client-supplied display name at session-create time. */
    private @Nullable String displayName;

    /**
     * Connection-profile of the client that originally created the session
     * (e.g. {@code "foot"}, {@code "web"}, {@code "mobile"}). Sessions can
     * only be resumed by a connection with the matching profile —
     * {@code session-bootstrap} returns an error otherwise. UIs should
     * surface this so users can spot incompatible sessions before picking.
     */
    private @Nullable String profile;
}
