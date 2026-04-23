/**
 * WebSocket inbound layer for the Vance Brain.
 *
 * Contents:
 * <ul>
 *   <li>{@link de.mhus.vance.brain.ws.ClientSession} — server-side session state</li>
 *   <li>{@link de.mhus.vance.brain.ws.ClientSessionRegistry} — session lifecycle</li>
 *   <li>{@link de.mhus.vance.brain.ws.VanceHandshakeInterceptor} — JWT validation + session bootstrap</li>
 *   <li>{@link de.mhus.vance.brain.ws.VanceWebSocketHandler} — frame dispatching, welcome / ping / logout handling</li>
 *   <li>{@link de.mhus.vance.brain.ws.WebSocketConfig} — Spring wiring</li>
 * </ul>
 *
 * See {@code specification/websocket-protokoll.md} for the wire contract.
 */
@NullMarked
package de.mhus.vance.brain.ws;

import org.jspecify.annotations.NullMarked;
