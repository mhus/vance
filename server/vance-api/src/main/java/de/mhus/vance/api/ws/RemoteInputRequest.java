package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Watcher → Brain → foot: one input line, handled exactly as if typed at the
 * JLine prompt — an open prompt claims it, a leading {@code /} dispatches a
 * slash command, anything else is chat.
 *
 * <p>Fire-and-forget by design: there is no acknowledgement across pods. The
 * effect appears in the output stream the watcher is already subscribed to,
 * which is also the honest failure mode — a line submitted into a reconnect gap
 * is dropped, and the watcher sees the client offline and its line missing
 * rather than getting a stale answer delivered minutes later
 * (planning/foot-remote-control.md §3.5).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteInputRequest {

    /** Target client. */
    private String clientId;

    /** The line to submit. */
    private String line;

    /** Watcher-generated id, echoed back on the resulting output batch. */
    private @Nullable String requestId;
}
