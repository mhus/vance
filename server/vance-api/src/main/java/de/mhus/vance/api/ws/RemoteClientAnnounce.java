package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Foot → Brain on the {@code clients} channel: "this CLI client exists and can
 * be remote-controlled".
 *
 * <p>Sent after every WELCOME frame, so a reconnect — including one that lands
 * on a different pod — rewrites the roster entry without anyone re-addressing
 * anything. {@link #clientId} is <b>process-stable</b>, not the WS session id:
 * a reconnect keeps it (the process lives), a restart legitimately gets a new
 * one because the working state is gone anyway.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteClientAnnounce {

    /** Process-stable client identity. Routing key of the whole channel. */
    private String clientId;

    /** Human-readable label, e.g. {@code "mba:~/sources/vance-wb (pid 4711)"}. */
    private String label;

    /** Hostname of the machine foot runs on. */
    private @Nullable String host;

    /** Working directory foot was started in. */
    private @Nullable String cwd;

    /** OS process id — part of what makes two foots on one machine tellable apart. */
    private long pid;

    /** Foot client version. */
    private @Nullable String version;

    /** Connection profile ({@code cli}, {@code daemon}, …). */
    private @Nullable String profile;

    /**
     * What this client accepts. {@code input} = lines may be submitted,
     * {@code interrupt} = pause/stop supported. A watcher greys out what is
     * missing instead of failing a call.
     */
    private @Nullable List<String> capabilities;

    /** Highest output sequence number produced so far (0 when nothing yet). */
    private long lastSeq;
}
