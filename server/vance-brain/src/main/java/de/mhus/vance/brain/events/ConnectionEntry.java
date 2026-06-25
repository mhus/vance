package de.mhus.vance.brain.events;

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
 *
 * <p>The {@link WebSocketSession} reference is the live transport
 * handle — closed when the connection drops, no need to hold a heavier
 * wrapper.
 */
public record ConnectionEntry(String userId, String editorId, WebSocketSession wsSession) {
}
