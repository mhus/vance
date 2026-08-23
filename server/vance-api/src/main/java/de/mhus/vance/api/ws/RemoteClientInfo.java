package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One row of the remote-client roster.
 *
 * <p>{@link #podId} is carried for diagnostics only — <b>nothing routes by
 * it</b>. Routing is keyed by {@code clientId} so a foot that reconnects onto
 * another pod stays reachable without the watcher learning anything new (see
 * planning/foot-remote-control.md §3.2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteClientInfo {

    /** Process-stable client identity. */
    private String clientId;

    /** Human-readable label. */
    private @Nullable String label;

    private @Nullable String host;
    private @Nullable String cwd;
    private long pid;
    private @Nullable String version;
    private @Nullable String profile;

    /** Bound session of that client, if any. */
    private @Nullable String sessionId;
    private @Nullable String projectId;

    /** {@code CHAT} or {@code FULLSCREEN}. */
    private @Nullable String uiMode;

    /** Whether a turn is running on that client. */
    private boolean busy;

    /** Whether the client accepts remote input right now. */
    private boolean acceptingInput;

    /** Why not, when it doesn't. */
    private @Nullable String inputBlockedReason;

    /** Highest output sequence number the client has produced. */
    private long lastSeq;

    /** ISO-8601 instant of the last heartbeat or announce. */
    private @Nullable String lastSeenAt;

    /** Diagnostics only — never a routing input. */
    private @Nullable String podId;
}
