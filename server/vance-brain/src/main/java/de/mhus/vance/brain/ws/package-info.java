/**
 * WebSocket inbound layer for the Vance Brain.
 *
 * Contents:
 * <ul>
 *   <li>{@link de.mhus.vance.brain.ws.ConnectionContext} — per-connection state
 *       (identity + optional session binding)</li>
 *   <li>{@link de.mhus.vance.brain.ws.VanceHandshakeInterceptor} — JWT validation + context bootstrap</li>
 *   <li>{@link de.mhus.vance.brain.ws.VanceWebSocketHandler} — frame dispatcher</li>
 *   <li>{@link de.mhus.vance.brain.ws.WsHandler} — per-message-type handler interface</li>
 *   <li>{@link de.mhus.vance.brain.ws.WebSocketSender} — shared outbound JSON writer</li>
 *   <li>{@link de.mhus.vance.brain.ws.WebSocketConfig} — Spring wiring</li>
 * </ul>
 *
 * See {@code specification/websocket-protokoll.md} for the wire contract.
 */
@NullMarked
package de.mhus.vance.brain.ws;

import org.jspecify.annotations.NullMarked;
