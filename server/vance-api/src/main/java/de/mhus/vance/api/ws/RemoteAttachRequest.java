package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Watcher → Brain → foot: start (or stop) watching a CLI client.
 *
 * <p>Attaching is what turns output streaming on at the foot end — a client
 * nobody watches publishes nothing. The attachment is kept alive by the
 * watcher's own heartbeat (a re-sent attach); when it stops, foot goes quiet
 * again after the TTL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteAttachRequest {

    /** Client to watch. */
    private String clientId;

    /**
     * Resume anchor: replay lines with a higher {@code seq}. {@code 0} means
     * "give me what you still have" — the whole retained ring.
     */
    private long sinceSeq;

    /**
     * Which watcher this is — <b>set by the brain</b>, never by the sender.
     *
     * <p>The client end counts attached watchers to decide whether to stream at
     * all, and two devices watching the same client must be two entries: with a
     * single shared key, the first detach would silence the stream for the
     * other one. Only the brain can name a watcher (its {@code editorId}); the
     * watcher's own frame reaches the client relayed, so the transport it
     * arrived on is the brain's, not the sender's.
     */
    private @Nullable String watcherId;
}
