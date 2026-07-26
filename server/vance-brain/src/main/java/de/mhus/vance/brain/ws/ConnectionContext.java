package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ClientContext;
import de.mhus.vance.shared.session.SessionDocument;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.WebSocketSession;

/**
 * Per-connection state living inside a single brain pod.
 *
 * <p>Created by {@link VanceHandshakeInterceptor} once JWT + profile +
 * client-version have been validated. At that point the identity (tenant /
 * user / displayName / profile / clientVersion / clientName) and the
 * {@code editorId} used for atomic session-binds are fixed — they don't
 * change for the lifetime of the HTTP upgrade.
 *
 * <p>A session is <em>not</em> bound at handshake. It is explicitly created
 * or resumed via the {@code session.create} / {@code session.resume} WebSocket
 * handlers, which flip {@link #hasSession()} to {@code true} by calling
 * {@link #bindSession(SessionDocument)}. Until that happens only pre-session
 * handlers (e.g. {@code session.list}, {@code project.list}) are allowed.
 *
 * <p>Cached fields on the document (userId, tenantId, profile, clientVersion)
 * are safe to read from memory — they don't change after creation. Anything
 * that might race with another pod (bind state, {@code lastActivityAt}) must
 * go through {@link de.mhus.vance.shared.session.SessionService}.
 */
@RequiredArgsConstructor
@Getter
public class ConnectionContext {

    private final String tenantId;
    private final String userId;
    private final @Nullable String displayName;
    /** Open-string profile token; canonical values in {@code de.mhus.vance.api.ws.Profiles}. */
    private final String profile;
    private final String clientVersion;
    private final @Nullable String clientName;
    private final String editorId;
    private final String podIp;

    private volatile @Nullable WebSocketSession webSocketSession;
    private volatile @Nullable SessionDocument sessionDocument;

    /**
     * Client-platform description parsed from the
     * {@link de.mhus.vance.api.ws.HandshakeHeaders#CLIENT_CONTEXT} header.
     * Handshake-final like the identity fields, but set just after
     * construction (mirrors {@link #attach}) so the many test call-sites
     * of the constructor stay untouched. {@code null} for web clients and
     * any client that doesn't send the header.
     */
    private volatile @Nullable ClientContext clientContext;

    /**
     * Per-connection cache of the resolved {@link de.mhus.vance.shared.permission.SecurityContext}
     * (user + team memberships). tenantId/userId are handshake-final, so this
     * is stable for the connection lifetime; without it every permission-checked
     * WS frame on the leitkanal re-queried team membership from Mongo. Populated
     * lazily by {@code SecurityContextFactory.fromConnection}. Mirrors the
     * per-request cache the HTTP path already keeps.
     */
    private volatile de.mhus.vance.shared.permission.@Nullable SecurityContext securityContext;

    public void attach(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    public void setClientContext(@Nullable ClientContext clientContext) {
        this.clientContext = clientContext;
    }

    public boolean hasSession() {
        return sessionDocument != null;
    }

    public void bindSession(SessionDocument document) {
        this.sessionDocument = document;
    }

    public void unbindSession() {
        this.sessionDocument = null;
    }

    public void cacheSecurityContext(
            de.mhus.vance.shared.permission.SecurityContext context) {
        this.securityContext = context;
    }

    public @Nullable String getSessionId() {
        SessionDocument doc = sessionDocument;
        return doc == null ? null : doc.getSessionId();
    }

    public @Nullable String getProjectId() {
        SessionDocument doc = sessionDocument;
        return doc == null ? null : doc.getProjectId();
    }
}
