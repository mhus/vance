package de.mhus.vance.api.ws;

/**
 * Canonical {@code type} values for the {@link WebSocketEnvelope}.
 *
 * See {@code specification/websocket-protokoll.md} §6 for the catalog.
 */
public final class MessageType {

    public static final String WELCOME = "welcome";
    public static final String PING = "ping";
    public static final String PONG = "pong";
    public static final String LOGOUT = "logout";
    public static final String ERROR = "error";

    public static final String SESSION_CREATE = "session-create";
    public static final String SESSION_RESUME = "session-resume";
    public static final String SESSION_LIST = "session-list";
    public static final String PROJECT_LIST = "project-list";
    public static final String PROJECTGROUP_LIST = "projectgroup-list";

    public static final String PROCESS_CREATE = "process-create";
    public static final String PROCESS_STEER = "process-steer";

    /** Compound command: session create/resume + processes + optional initial message. */
    public static final String SESSION_BOOTSTRAP = "session-bootstrap";

    /** Server-initiated notification: a chat message was appended to a process's log. */
    public static final String CHAT_MESSAGE_APPENDED = "chat-message-appended";

    /** Client → brain: declare the tools this connection exposes for the current session. */
    public static final String CLIENT_TOOL_REGISTER = "client-tool-register";

    /** Brain → client: invoke a client-registered tool. */
    public static final String CLIENT_TOOL_INVOKE = "client-tool-invoke";

    /** Client → brain: the result of a prior {@link #CLIENT_TOOL_INVOKE}. */
    public static final String CLIENT_TOOL_RESULT = "client-tool-result";

    private MessageType() {
    }
}
