package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A generic ephemeral, document-scoped signal on the {@code signals} channel.
 *
 * <p>Fire-and-forget, best-effort — no roster, no history, not replayed on
 * reconnect, <b>never persisted</b>. Delivered only to current subscribers of
 * {@link #path}; cross-pod fan-out via Redis (pod-local without Redis).
 *
 * <p>The {@link #signal} field discriminates the kind of signal and
 * {@link #data} carries its free-form payload — the framework passes
 * {@code data} through verbatim and never reads it. New signal kinds ride the
 * same transport without new wire types. First producer: the compose run
 * status (`signal = "compose-run"`, `data = { runId, status, workspace }`);
 * a future run-kill would be `signal = "compose-kill"` on the same channel.
 *
 * <pre>{@code
 * { "channel": "signals",
 *   "payload": { "type": "signal",
 *                "data": { "path": "notes/build.compose.yaml",
 *                          "signal": "compose-run",
 *                          "data": { "runId": "cr-1a2b", "status": "running",
 *                                    "workspace": "build" } } } }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SignalFrame {

    /** Document path this signal is scoped to (subscription key). */
    private String path;

    /** Discriminator for the kind of signal, e.g. {@code "compose-run"}. */
    private String signal;

    /** Free-form, signal-specific payload. Passed through verbatim. */
    private @Nullable Map<String, Object> data;
}
