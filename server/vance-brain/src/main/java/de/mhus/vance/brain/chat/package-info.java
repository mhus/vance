/**
 * REST surface for chat-history reads. The push side of chat
 * (streaming chunks, append commits) lives on the WebSocket — see
 * {@code de.mhus.vance.api.chat} and the {@code chat-message-*} frames
 * in {@code MessageType}. This package only exposes a pull endpoint
 * so the web chat editor can render the back-log on mount.
 */
@NullMarked
package de.mhus.vance.brain.chat;

import org.jspecify.annotations.NullMarked;
