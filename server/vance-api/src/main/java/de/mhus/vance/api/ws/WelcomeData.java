package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@link MessageType#WELCOME} message — first frame the server sends
 * after a successful handshake.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class WelcomeData {

    private String sessionId;

    private boolean sessionResumed;

    private String userId;

    private @Nullable String displayName;

    private @Nullable String tenantId;

    private ServerInfo server;
}
