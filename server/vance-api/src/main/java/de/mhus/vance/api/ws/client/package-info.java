/**
 * WebSocket client implementation for speaking to the Vance Brain.
 *
 * Built on the JDK-native {@link java.net.http.WebSocket} so {@code vance-api}
 * stays free of external WebSocket dependencies — only Jackson (already present)
 * is used for framing.
 *
 * Entry point: {@link de.mhus.vance.api.ws.client.VanceWebSocketClient}.
 */
@NullMarked
package de.mhus.vance.api.ws.client;

import org.jspecify.annotations.NullMarked;
