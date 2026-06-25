package de.mhus.vance.brain.events;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks live {@link WebSocketSession} connections per Vance session so
 * brain-side code can push notifications without threading the socket
 * through every call.
 *
 * <p>A session may hold <em>more than one</em> connection in
 * collaboration mode (see {@code planning/multi-user-sessions.md} §2.1).
 * In private (legacy) mode it holds at most one connection — the
 * single-user behaviour is preserved at the {@link
 * #register(String, String, String, WebSocketSession, boolean)} call.
 *
 * <p>One pod serves a given session at a time — no cross-pod routing
 * here. Cross-pod broadcast comes in a later step (see §3.5 of the plan).
 *
 * <p>Not every brain action needs a live connection: if nobody is
 * listening, {@link #find}/{@link #findAll} simply return empty and
 * callers drop the event.
 */
@Component
@Slf4j
public class SessionConnectionRegistry {

    /**
     * sessionId → connection list. Each list is wrapped in a synchronised
     * view at access time; the outer map is {@link ConcurrentHashMap}.
     * Lists are tiny (typically 1, rarely 2-3) so iteration cost is
     * irrelevant.
     */
    private final Map<String, List<ConnectionEntry>> bySession = new ConcurrentHashMap<>();

    /**
     * Outcome of a {@link #register} call.
     */
    public enum RegisterOutcome {
        /** Connection accepted, no prior connection. */
        ACCEPTED,
        /** Connection accepted; an earlier connection of the same user was kicked. */
        KICKED_OLD,
        /**
         * Connection rejected because the session is private and a
         * different user already holds it.
         */
        REJECTED
    }

    /**
     * Result of a {@link #register} call. When {@link #outcome} is
     * {@link RegisterOutcome#KICKED_OLD}, {@link #kicked} is the prior
     * {@link WebSocketSession} of the same user — the caller is
     * expected to close it. When {@link RegisterOutcome#REJECTED}, the
     * new connection was <em>not</em> stored and the caller must close
     * the incoming socket.
     */
    public record RegisterResult(RegisterOutcome outcome, @Nullable WebSocketSession kicked) {
        public static RegisterResult accepted() {
            return new RegisterResult(RegisterOutcome.ACCEPTED, null);
        }

        public static RegisterResult kickedOld(WebSocketSession old) {
            return new RegisterResult(RegisterOutcome.KICKED_OLD, old);
        }

        public static RegisterResult rejected() {
            return new RegisterResult(RegisterOutcome.REJECTED, null);
        }
    }

    /**
     * Registers a connection for a session. Behaviour depends on
     * {@code allowMultipleClients} (from the session's
     * {@code SessionDocument.allowMultipleClients} flag):
     *
     * <ul>
     *   <li>If a previous connection of the <em>same</em> {@code userId}
     *       is bound, it is removed and returned in
     *       {@link RegisterResult#kicked()} — kick-old semantics. The
     *       caller closes the old socket.</li>
     *   <li>If a connection of a <em>different</em> {@code userId} is
     *       bound and {@code allowMultipleClients == false}, the new
     *       connection is rejected
     *       ({@link RegisterOutcome#REJECTED}).</li>
     *   <li>Otherwise the new connection is appended
     *       ({@link RegisterOutcome#ACCEPTED}).</li>
     * </ul>
     */
    public RegisterResult register(
            String sessionId,
            String userId,
            String editorId,
            WebSocketSession wsSession,
            boolean allowMultipleClients) {
        ConnectionEntry incoming = new ConnectionEntry(userId, editorId, wsSession);
        final WebSocketSession[] kickedHolder = new WebSocketSession[1];
        final RegisterOutcome[] outcomeHolder = {RegisterOutcome.ACCEPTED};

        bySession.compute(sessionId, (key, existing) -> {
            List<ConnectionEntry> list = existing != null ? existing : new ArrayList<>();
            ConnectionEntry sameUser = null;
            ConnectionEntry otherUser = null;
            for (ConnectionEntry entry : list) {
                if (entry.userId().equals(userId)) {
                    sameUser = entry;
                    break;
                } else {
                    otherUser = entry;
                }
            }
            if (sameUser != null) {
                list.remove(sameUser);
                list.add(incoming);
                kickedHolder[0] = sameUser.wsSession();
                outcomeHolder[0] = RegisterOutcome.KICKED_OLD;
                return list;
            }
            if (!allowMultipleClients && otherUser != null) {
                outcomeHolder[0] = RegisterOutcome.REJECTED;
                return list;
            }
            list.add(incoming);
            return list;
        });

        switch (outcomeHolder[0]) {
            case ACCEPTED ->
                    log.debug("registry accepted session='{}' user='{}' editor='{}'",
                            sessionId, userId, editorId);
            case KICKED_OLD ->
                    log.debug("registry kicked-old session='{}' user='{}' editor='{}'",
                            sessionId, userId, editorId);
            case REJECTED ->
                    log.info("registry rejected session='{}' user='{}' editor='{}' "
                                    + "(private session, another user connected)",
                            sessionId, userId, editorId);
        }
        return new RegisterResult(outcomeHolder[0], kickedHolder[0]);
    }

    /**
     * Removes a single connection identified by {@code editorId}. Call
     * on per-connection close. Drops the session entry entirely once
     * the list becomes empty.
     */
    public void unregister(String sessionId, String editorId) {
        bySession.computeIfPresent(sessionId, (key, list) -> {
            list.removeIf(entry -> entry.editorId().equals(editorId));
            return list.isEmpty() ? null : list;
        });
        log.debug("registry unregistered session='{}' editor='{}'", sessionId, editorId);
    }

    /**
     * Removes <em>all</em> connections for a session. Call on
     * session-close / session-unbind to wipe the slate.
     */
    public void unregisterAll(String sessionId) {
        List<ConnectionEntry> removed = bySession.remove(sessionId);
        if (removed != null && !removed.isEmpty()) {
            log.debug("registry unregisteredAll session='{}' n={}", sessionId, removed.size());
        }
    }

    /**
     * Legacy single-connection lookup — returns the first connection
     * for the session, if any. Callers that need every connection
     * (broadcast paths) must use {@link #findAll}.
     */
    public Optional<WebSocketSession> find(@Nullable String sessionId) {
        if (sessionId == null) return Optional.empty();
        List<ConnectionEntry> list = bySession.get(sessionId);
        if (list == null || list.isEmpty()) return Optional.empty();
        return Optional.of(list.get(0).wsSession());
    }

    /**
     * Returns all connections currently bound to the session — empty
     * collection when no one is connected. Snapshot copy: safe to
     * iterate without holding a lock.
     */
    public Collection<WebSocketSession> findAll(@Nullable String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        List<ConnectionEntry> list = bySession.get(sessionId);
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<WebSocketSession> out = new ArrayList<>(list.size());
        for (ConnectionEntry entry : list) {
            out.add(entry.wsSession());
        }
        return out;
    }

    /**
     * Returns the connection of a specific user in the given session,
     * if any. Use for user-scoped pushes (inbox notifications,
     * per-user dialogs) where a multi-user session must <em>not</em>
     * leak the frame to other users sharing the channel.
     *
     * <p>At most one entry per (sessionId, userId) — the kick-old
     * rule on {@link #register} guarantees that.
     */
    public Optional<WebSocketSession> findForUser(
            @Nullable String sessionId, @Nullable String userId) {
        if (sessionId == null || userId == null) return Optional.empty();
        List<ConnectionEntry> list = bySession.get(sessionId);
        if (list == null) return Optional.empty();
        for (ConnectionEntry entry : list) {
            if (userId.equals(entry.userId())) {
                return Optional.of(entry.wsSession());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the number of active connections for the session.
     * Reflects the live mode (1 = solo, &gt;1 = collab).
     */
    public int connectionCount(@Nullable String sessionId) {
        if (sessionId == null) return 0;
        List<ConnectionEntry> list = bySession.get(sessionId);
        return list == null ? 0 : list.size();
    }

    /**
     * Convenience for the connection-bind callers: closes the prior
     * {@link WebSocketSession} returned in a
     * {@link RegisterOutcome#KICKED_OLD} result. No-op for
     * {@link RegisterOutcome#ACCEPTED} / {@link RegisterOutcome#REJECTED}
     * — see the per-outcome semantics on {@link RegisterResult}.
     */
    public static void closeKicked(RegisterResult result) {
        if (result.outcome() != RegisterOutcome.KICKED_OLD) return;
        WebSocketSession kicked = result.kicked();
        if (kicked == null || !kicked.isOpen()) return;
        try {
            kicked.close(CloseStatus.POLICY_VIOLATION.withReason(
                    "Replaced by newer connection of the same user"));
        } catch (IOException e) {
            log.debug("Closing kicked WebSocketSession failed: {}", e.toString());
        }
    }
}
