/**
 * WebSocket wire-format classes shared between Vance clients and the Brain server.
 *
 * Contains the generic message envelope, the message-type constants, and the typed
 * {@code data} payloads for the messages documented in
 * {@code specification/websocket-protokoll.md}.
 *
 * Pure POJOs — no Spring, no business logic. This package is the single source of
 * truth for the WebSocket contract and is the input to the TypeScript generator.
 */
@NullMarked
package de.mhus.vance.api.ws;

import org.jspecify.annotations.NullMarked;
