package de.mhus.vance.shared.thinkprocess;

/**
 * Discriminator for {@link PendingMessageDocument}. Mirrors the
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
    EXTERNAL_COMMAND,
    /** An inbox item this process was blocked on has been answered. */
    INBOX_ANSWER,
    /**
     * A peer hub-process (e.g. another Vance session of the same user)
     * notifies us of a relevant action. Hub-only — see
     * {@code specification/vance-engine.md} §5.3.
     */
    PEER_EVENT
}
