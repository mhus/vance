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
    public static final String SESSION_UNBIND = "session-unbind";
    public static final String SESSION_LIST = "session-list";
    public static final String PROJECT_LIST = "project-list";
    public static final String PROJECTGROUP_LIST = "projectgroup-list";

    public static final String PROCESS_CREATE = "process-create";
    public static final String PROCESS_STEER = "process-steer";
    public static final String PROCESS_LIST = "process-list";

    /**
     * Client → brain: stop a running think-process in the bound
     * session. User-initiated counterpart to the orchestrator-only
     * {@code process_stop} brain-tool. Triggers
     * {@code engine.stop(...)} on the target's lane and transitions
     * the process to {@code CLOSED} with {@code closeReason=STOPPED}.
     *
     * Use the softer {@link #PROCESS_PAUSE} for the "halt-and-correct"
     * UX — full close is intended for explicit teardown.
     */
    public static final String PROCESS_STOP = "process-stop";

    /**
     * Client → brain: pause one or more running think-processes. With
     * empty {@code processName}, the brain pauses all non-CLOSED
     * children of the bound session's chat-process — the typical
     * "user pressed ESC, wants to redirect" flow. Workers transition
     * to {@code PAUSED}; the chat-process itself is untouched.
     *
     * <p>Resume happens through the orchestrator (Arthur via
     * {@code process_resume} tool) once the user has clarified.
     * See {@code specification/session-lifecycle.md} §11.2.
     */
    public static final String PROCESS_PAUSE = "process-pause";

    /**
     * Client → brain: resume a previously paused think-process.
     * Symmetric counterpart to {@link #PROCESS_PAUSE}. Mostly for
     * tests and admin UIs — the regular resume path goes through
     * Arthur deciding via the {@code process_resume} brain-tool.
     */
    public static final String PROCESS_RESUME = "process-resume";

    /** Manual memory compaction trigger for a think-process. */
    public static final String PROCESS_COMPACT = "process-compact";

    /**
     * Activate / deactivate / list skills on a think-process.
     * Out-of-band steering signal — does not trigger an LLM turn,
     * only mutates the process's {@code activeSkills} list (or returns
     * a list view). See {@code specification/skills.md} §6.
     */
    public static final String PROCESS_SKILL = "process-skill";

    /** Compound command: session create/resume + processes + optional initial message. */
    public static final String SESSION_BOOTSTRAP = "session-bootstrap";

    /** Server-initiated notification: a chat message was appended to a process's log. */
    public static final String CHAT_MESSAGE_APPENDED = "chat-message-appended";

    /**
     * Server-initiated notification: a progressive chunk of an assistant reply
     * is available. The authoritative commit is still {@link #CHAT_MESSAGE_APPENDED} —
     * clients treat chunks as optimistic rendering to be discarded on commit.
     */
    public static final String CHAT_MESSAGE_STREAM_CHUNK = "chat-message-stream-chunk";

    /** Client → brain: declare the tools this connection exposes for the current session. */
    public static final String CLIENT_TOOL_REGISTER = "client-tool-register";

    /** Brain → client: invoke a client-registered tool. */
    public static final String CLIENT_TOOL_INVOKE = "client-tool-invoke";

    /** Client → brain: the result of a prior {@link #CLIENT_TOOL_INVOKE}. */
    public static final String CLIENT_TOOL_RESULT = "client-tool-result";

    /**
     * Client → brain: upload the local {@code agent.md} (or whatever
     * client-side agent doc) so the brain can splice it into the
     * conversation's memory block. One-shot, snapshot-at-bind. Stored
     * on the bound session; surviving until session close or a new
     * upload (e.g. via foot's {@code /reload}). Only consumed when the
     * recipe's profile-block opts in via
     * {@code params.useClientAgentDoc=true}.
     */
    public static final String CLIENT_AGENT_UPLOAD = "client-agent-upload";

    // ─── User-Interaction (Inbox) Subsystem ──────────────────────

    /** Client → brain: list inbox items for the bound user/session. */
    public static final String INBOX_LIST = "inbox-list";

    /** Client → brain: fetch one item by id. */
    public static final String INBOX_ITEM = "inbox-item";

    /** Client → brain: submit an answer for a PENDING item. */
    public static final String INBOX_ANSWER = "inbox-answer";

    /** Client → brain: delegate an item to another user. */
    public static final String INBOX_DELEGATE = "inbox-delegate";

    /** Client → brain: archive an item (move out of live view). */
    public static final String INBOX_ARCHIVE = "inbox-archive";

    /** Client → brain: dismiss a pending item without a substantive answer. */
    public static final String INBOX_DISMISS = "inbox-dismiss";

    /** Brain → client: a new item has been created for the receiving user. */
    public static final String INBOX_ITEM_ADDED = "inbox-item-added";

    /** Brain → client: an item's status / assignment changed. */
    public static final String INBOX_ITEM_UPDATED = "inbox-item-updated";

    /** Brain → client (welcome-time): summary of pending items waiting. */
    public static final String INBOX_PENDING_SUMMARY = "inbox-pending-summary";

    // ─── User-Progress Side-Channel ──────────────────────────────

    /**
     * Server-initiated notification: live progress update from a running
     * think-process. Unified envelope ({@code ProcessProgressNotification})
     * with three payload variants (metrics / plan / status) discriminated
     * by {@code kind}. Side-channel — never enters conversation history.
     *
     * See {@code specification/user-progress-channel.md}.
     */
    public static final String PROCESS_PROGRESS = "process-progress";

    private MessageType() {
    }
}
