package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of the {@link MessageType#PONG} message — keep-alive ACK from the server.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class PongData {

    /** Echoed {@code clientTimestamp} of the matching ping. */
    private long clientTimestamp;

    /** Unix-millis timestamp taken on the server at send time. */
    private long serverTimestamp;
}
