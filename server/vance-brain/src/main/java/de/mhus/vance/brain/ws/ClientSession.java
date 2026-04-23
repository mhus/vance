package de.mhus.vance.brain.ws;

import de.mhus.vance.shared.session.SessionDocument;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.WebSocketSession;

/**
 * Per-connection binding living inside a single brain pod.
 *
 * <p>Wraps the persistent {@link SessionDocument} together with this pod's
 * {@code connectionId} (the UUID that won the atomic bind) and the local
 * {@link WebSocketSession} reference once the HTTP upgrade has completed.
 *
 * <p>Cached fields on the document (userId, tenantId, clientType, clientVersion)
 * are safe to read from memory — they don't change after creation. Anything
 * that might race with another pod (bind state, lastActivityAt) must go through
 * {@link de.mhus.vance.shared.session.SessionService}.
 */
@RequiredArgsConstructor
@Getter
public class ClientSession {

    private final SessionDocument document;
    private final String connectionId;

    private volatile @Nullable WebSocketSession webSocketSession;

    public String getSessionId() {
        return document.getSessionId();
    }

    public String getUserId() {
        return document.getUserId();
    }

    public @Nullable String getDisplayName() {
        return document.getDisplayName();
    }

    public String getTenantId() {
        return document.getTenantId();
    }

    public void attach(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }
}
