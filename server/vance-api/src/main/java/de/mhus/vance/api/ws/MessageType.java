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

    /**
     * Client → brain: patch the user-facing metadata of the bound
     * session ({@code title}, {@code icon}, {@code color},
     * {@code tags}, {@code pinned}). Mirrors the REST
     * {@code PATCH /sessions/{id}/metadata} endpoint — exposed over
     * WS so that {@code foot} (WS-only) can edit metadata too.
     * Reply: {@code SessionMetadataDto} carrying the post-patch state.
     * See {@code specification/session-lifecycle.md} §14.2.
     */
    public static final String SESSION_METADATA_PATCH = "session-metadata-patch";
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

    /**
     * Client → brain: register a JetBrains MCP server endpoint with
     * the brain's tool registry. Triggered by the foot's
     * {@code --intellij-mcp[=<url>]} switch. The brain upserts a
     * {@code mcp_server} {@code ServerToolDocument} in the tenant's
     * {@code _vance} system project, so all 40+ IntelliJ tools become
     * callable from any project. Idempotent — same URL re-registers as
     * a no-op, different URL updates the doc in place.
     */
    public static final String INTELLIJ_MCP_REGISTER = "intellij-mcp-register";

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

    // ─── Plan-Mode Notifications (Arthur) ─────────────────────────

    /**
     * Brain → client: a think-process changed its
     * {@link de.mhus.vance.api.thinkprocess.ProcessMode}. Carries the
     * old + new mode plus the process id. Drives Foot's mode-indicator
     * in the prompt and the plan-box visibility in the scrollback.
     *
     * See {@code readme/arthur-plan-mode.md} §9.
     */
    public static final String PROCESS_MODE_CHANGED = "process-mode-changed";

    /**
     * Brain → client: TodoList of a think-process was created or
     * updated. Payload carries the full current TodoList — clients
     * replace their local copy verbatim. Foot renders this above the
     * prompt as a scrollable status block.
     *
     * See {@code readme/arthur-plan-mode.md} §9.
     */
    public static final String TODOS_UPDATED = "todos-updated";

    /**
     * Brain → client: a Plan was proposed by Arthur (PROPOSE_PLAN).
     * Sent <em>in addition</em> to the regular ChatMessage stream so
     * UIs can highlight the plan-text visually (box, color, "Plan v2"
     * header). Payload carries summary + plan-version (count of plans
     * proposed in this process so far). The full plan text is in the
     * ChatMessage.
     *
     * See {@code readme/arthur-plan-mode.md} §9.
     */
    public static final String PLAN_PROPOSED = "plan-proposed";

    // ─── Eddie Mediation (specification/eddie-engine.md §8.5) ───

    /**
     * Server → client (Eddie's session): instructs the client to
     * {@code session-rebind} to a worker session — Eddie is handing
     * the live conversation directly to a worker (typically Arthur)
     * because the user needs client-side tools Eddie can't route.
     * Payload: {@code MediateHandoverNotification} with the target
     * session id and human-readable hints.
     */
    public static final String MEDIATE_HANDOVER = "mediate-handover";

    /**
     * Client → server (worker's session): user pressed {@code /hub}
     * (or the Web-UI back-to-hub button). Server intercepts the frame,
     * runs the mediation-end choreography, and rebinds the client back
     * to Eddie's session. See
     * {@code specification/engine-message-routing.md} §4.1.2.
     */
    public static final String MEDIATION_END = "mediation-end";

    // ─── File Transfer Subsystem ────────────────────────────────

    /**
     * Sender → receiver: opens a file transfer with target path,
     * total size, hash and optional attrs. See
     * {@code specification/file-transfer.md} §4.
     */
    public static final String TRANSFER_INIT = "transfer-init";

    /** Receiver → sender: ack/nack of a {@link #TRANSFER_INIT}. */
    public static final String TRANSFER_INIT_RESPONSE = "transfer-init-response";

    /**
     * Sender → receiver: one chunk of file content. {@code last=true}
     * marks the final chunk.
     */
    public static final String TRANSFER_CHUNK = "transfer-chunk";

    /**
     * Receiver → sender: signals completion (or mid-stream failure).
     * Carries hash check result and bytes-written.
     */
    public static final String TRANSFER_COMPLETE = "transfer-complete";

    /**
     * Sender → receiver: closes the transfer lifecycle and lets
     * the receiver drop the transferId from its pending map.
     */
    public static final String TRANSFER_FINISH = "transfer-finish";

    /**
     * Brain → Foot: trigger an upload. Foot will reply with
     * {@link #TRANSFER_INIT} (Foot is sender) or {@link #TRANSFER_FINISH}
     * with {@code ok=false} on local failure.
     */
    public static final String CLIENT_FILE_UPLOAD_REQUEST = "client-file-upload-request";

    // ─── Cross-Side Execution Registry ───────────────────────────

    /**
     * Foot → Brain: push a single shell-job life-cycle event
     * (started / tick / ended). Drives the brain's
     * {@code ExecutionRegistryService} so the brain knows about
     * foot-side executions even outside the LLM tool-call window.
     */
    public static final String EXEC_EVENT = "exec-event";

    /**
     * Foot → Brain: send the full list of currently known foot-side
     * jobs. Sent at connect / reconnect for reconciliation; brain
     * replaces every entry it owns for this connection.
     */
    public static final String EXEC_LIST_SNAPSHOT = "exec-list-snapshot";

    /**
     * Client → Brain: start a Hactar workflow run in the bound
     * session's project. Payload:
     * {@link de.mhus.vance.api.hactar.HactarWorkflowStartRequest}
     * (workflow name + optional params). Reply:
     * {@link de.mhus.vance.api.hactar.HactarWorkflowStartResponse}
     * carrying the new {@code workflowRunId}.
     *
     * <p>Symmetric to {@code POST .../workflows/{name}/start}.
     * See {@code planning/workflow-service.md} §8.3.
     */
    public static final String WORKFLOW_START = "workflow-start";

    /**
     * Script Cortex execution lifecycle — pushed to the WebSocket-session
     * that subscribed to a given {@code executionId} via
     * {@link #SCRIPT_EXECUTION_SUBSCRIBE}. See
     * {@code planning/script-cortex.md} §"WS-Messages".
     */
    public static final String SCRIPT_EXECUTION_STARTED = "script-execution-started";
    public static final String SCRIPT_EXECUTION_LOG = "script-execution-log";
    public static final String SCRIPT_EXECUTION_FINISHED = "script-execution-finished";
    public static final String SCRIPT_EXECUTION_FAILED = "script-execution-failed";
    public static final String SCRIPT_EXECUTION_CANCELLED = "script-execution-cancelled";

    /**
     * Client-initiated subscription to a Script Cortex execution.
     * Payload: {@code { executionId: string }}. After subscribing the
     * client receives all {@code script-execution-*} notifications for
     * that id. Unsubscribed on WebSocket close.
     */
    public static final String SCRIPT_EXECUTION_SUBSCRIBE = "script-execution-subscribe";

    private MessageType() {
    }
}
