package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@link MessageType#WELCOME} message — first frame the server
 * sends after a successful handshake.
 *
 * <p>The connection starts <em>without</em> a bound session. The client must
 * drive one of {@link MessageType#SESSION_CREATE} / {@link MessageType#SESSION_RESUME}
 * / {@link MessageType#SESSION_LIST} before any session-scoped command is
 * accepted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class WelcomeData {

    private String userId;

    private @Nullable String displayName;

    private String tenantId;

    private ServerInfo server;
}
