package de.mhus.vance.brain.events;

import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.WebSocketSession;

/**
 * One live client connection bound to a Vance session — see
 * {@link SessionConnectionRegistry}.
 *
 * <p>A session in collaboration mode (see
 * {@code planning/multi-user-sessions.md} §2.1) can hold multiple
 * concurrent connections; this record identifies each one by its
 * {@code editorId} and carries the {@code userId} for the kick-old
 * (same user) vs. reject (different user, private session) decision.
 * {@code displayName} is captured from the
 * {@code ConnectionContext} at registration time so participant
 * lists, avatar badges and prompt-render {@code participants}
 * variables can show readable names without a per-frame
 * UserService lookup.
 *
 * <p>The {@link WebSocketSession} reference is the live transport
 * handle — closed when the connection drops, no need to hold a heavier
 * wrapper.
 */
public record ConnectionEntry(
        String userId,
        String editorId,
        @Nullable String displayName,
        WebSocketSession wsSession) {
}
