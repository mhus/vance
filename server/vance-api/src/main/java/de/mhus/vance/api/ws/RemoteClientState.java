package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The live state of a remote-controlled CLI client — everything the pinned
 * terminal UI shows and the line stream therefore does not: connection state,
 * bound session, which surface owns the TTY, whether a turn is running.
 *
 * <p>Doubles as the heartbeat payload ({@link MessageType#CLIENT_HEARTBEAT}) so
 * a roster refresh and a state push are the same frame — the roster would
 * otherwise carry a second, staler copy of the same facts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteClientState {

    /** Process-stable client identity. */
    private String clientId;

    /** Foot's own view of its brain connection ({@code OPEN}, …). */
    private @Nullable String connection;

    /** Bound session, or {@code null} when foot has none. */
    private @Nullable String sessionId;

    /** Project of the bound session. */
    private @Nullable String projectId;

    /**
     * Which surface owns the terminal: {@code CHAT} (JLine REPL) or
     * {@code FULLSCREEN} (Lanterna excursion). Remote input is rejected while
     * {@code FULLSCREEN} — see planning/foot-remote-control.md §2.
     */
    private @Nullable String uiMode;

    /** Output verbosity threshold. */
    private @Nullable String verbosity;

    /** Whether a chat turn is currently in flight. */
    private boolean busy;

    /** Highest output sequence number produced so far. */
    private long lastSeq;

    /**
     * Whether remote input is currently accepted. False while foot is in a
     * Lanterna excursion or the remote-control mode still needs a local
     * {@code /remote allow}.
     */
    private boolean acceptingInput;

    /** Why input is not accepted — shown verbatim to the watcher. */
    private @Nullable String inputBlockedReason;
}
