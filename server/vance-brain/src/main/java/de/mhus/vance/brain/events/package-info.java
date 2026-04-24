/**
 * Server-to-client notification plumbing.
 *
 * <p>Think-engines and other brain-side components push events to a
 * connected client through {@link
 * de.mhus.vance.brain.events.ClientEventPublisher}, which routes to
 * the WebSocketSession bound to the session via {@link
 * de.mhus.vance.brain.events.SessionConnectionRegistry}.
 */
@NullMarked
package de.mhus.vance.brain.events;

import org.jspecify.annotations.NullMarked;
