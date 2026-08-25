package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reply to a {@link MessageType#SESSION_RESUME} request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SessionResumeResponse {

    private String sessionId;

    private String projectId;

    /**
     * Name of the session's chat-process (typically {@code "chat"}),
     * if one is registered on the session. Lets the client set its
     * active-process pointer right after resume without an extra
     * round-trip — same convenience {@link SessionBootstrapResponse}
     * already provides on the bootstrap path.
     *
     * <p>{@code null} when the session has no chat-process registered
     * (rare; expected only for legacy sessions or daemon-driven flows).
     */
    private @org.jspecify.annotations.Nullable String chatProcessName;

    /**
     * The session's processes that are mid-turn right now ({@code RUNNING} or
     * {@code INIT}) — empty when the session is quiet.
     *
     * <p>A turn outlives the connection that started it, but a client's busy
     * state does not: the web composer loses its pending {@code process-steer}
     * promise, foot's {@link de.mhus.vance.api.progress.StatusTag} counter
     * loses the {@code ENGINE_TURN_START} whose {@code _END} is still coming.
     * Either way the engine keeps working and its answers keep arriving while
     * the UI says idle — which reads as the agent acting on its own.
     *
     * <p>The reply is the right carrier, and not only for the saved
     * round-trip: it travels the same connection as the progress pings, so a
     * turn that ends right after this was computed is reported by an
     * {@code ENGINE_TURN_END} that provably arrives <em>after</em> it. A
     * separate "which processes are running" query has no such ordering and
     * can leave a spinner nothing will ever close.
     */
    private java.util.List<de.mhus.vance.api.thinkprocess.ActiveProcessRef> activeProcesses;
}
