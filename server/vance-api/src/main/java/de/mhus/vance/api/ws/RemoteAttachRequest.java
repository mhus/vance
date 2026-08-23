package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
