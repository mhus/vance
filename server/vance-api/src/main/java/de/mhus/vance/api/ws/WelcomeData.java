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

    /**
     * Per-connection identifier the server assigned at handshake time.
     * Client surfaces it back to the server on REST writes via the
     * {@code X-Editor-Id} header so the live-broadcast layer can filter
     * the writer's own connection out of the {@code documents.changed}
     * fan-out — no self-banner on the tab that triggered the save.
     *
     * <p>Stable for the lifetime of the WebSocket; a reconnect produces a
     * fresh value (and a fresh banner-eligibility, which is what users
     * expect: "I crashed, this is a new editor instance now").
     */
    private String editorId;

    private ServerInfo server;
}
