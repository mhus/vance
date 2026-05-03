package de.mhus.vance.shared.chat;

/**
 * Spring application event published by {@link ChatMessageService#append}
 * after a chat-message has been persisted. The brain-side notification
 * dispatcher subscribes to this and fans the message out to the
 * connected WebSocket client (when one exists for the message's
 * session).
 *
 * <p>Direct WebSocket-handler call-sites used to push
 * {@code CHAT_MESSAGE_APPENDED} themselves right after invoking the
 * engine, but that path missed any chat-message produced by an
 * Auto-Wakeup turn (worker {@code ProcessEvent}-driven, not directly
 * triggered by the user) — those wrote silently to Mongo. This event
 * makes the path uniform: every persisted chat-message goes through
 * the same dispatcher.
 */
public record ChatMessageAppendedEvent(ChatMessageDocument message) {
}
