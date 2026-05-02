/**
 * Cross-pod routing for {@code EngineMessage}s. Server-side WebSocket
 * endpoint, client-side connector, and the router that decides
 * local-direct vs. remote-WS dispatch.
 *
 * <p>Local handling (same-pod sender → same-pod receiver) stays in
 * {@code vance-shared}'s {@code EngineMessageService} — this package
 * only kicks in when the target process's Home Pod is a different
 * brain process. See {@code specification/engine-message-routing.md}
 * §4-§7.
 */
@NullMarked
package de.mhus.vance.brain.enginemessage;

import org.jspecify.annotations.NullMarked;
