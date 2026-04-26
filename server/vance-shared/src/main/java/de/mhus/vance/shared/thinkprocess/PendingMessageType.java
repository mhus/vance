package de.mhus.vance.shared.thinkprocess;

/**
 * Discriminator for {@link PendingMessageDocument}. Mirrors the four
 * variants of {@code SteerMessage} on the engine side; kept here so
 * {@code vance-shared} stays free of the engine layer.
 */
public enum PendingMessageType {
    /** The user typed in the chat. Engine-side: {@code SteerMessage.UserChatInput}. */
    USER_CHAT_INPUT,
    /** A sibling/child process emitted a life-cycle or progress event. */
    PROCESS_EVENT,
    /** Result of an asynchronously-dispatched tool call. */
    TOOL_RESULT,
    /** A high-level command from the client (UI button, slash-command). */
    EXTERNAL_COMMAND
}
