package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Server metadata delivered inside {@link WelcomeData#getServer()}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ServerInfo {

    /** SemVer of the Brain build. */
    private String version;

    /** Integer wire-protocol version; bumped on incompatible changes. */
    private int protocolVersion;

    /** Seconds between expected client {@code ping} messages. */
    private int pingInterval;

    /** Capability flags the server exposes (feature gating for the client). */
    private List<String> capabilities;
}
