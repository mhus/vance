/**
 * Client-registered tools skeleton.
 *
 * <p>Clients declare their local tools over WebSocket; the brain adapts
 * them to the server-side {@link de.mhus.vance.brain.tools.Tool}
 * interface and routes invocations back to the same connection. The
 * runtime wiring is stubbed — the registry, source, and message
 * handlers are in place so the protocol can be exercised; a real client
 * needs to send {@code client-tool-register} and answer
 * {@code client-tool-invoke} with {@code client-tool-result}.
 */
@NullMarked
package de.mhus.vance.brain.tools.client;

import org.jspecify.annotations.NullMarked;
