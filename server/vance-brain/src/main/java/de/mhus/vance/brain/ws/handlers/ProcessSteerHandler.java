package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.IdeContext;
import de.mhus.vance.api.thinkprocess.IdeFileRange;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.chat.ChatMentionParser;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound user chat → durable inbox → lane-drained engine work.
 *
 * <p><b>Two phases.</b>
 * <ol>
 *   <li><i>Receive thread</i> — validate, look up the process,
 *       atomically append a {@code USER_CHAT_INPUT}
 *       {@link PendingMessageDocument} to its pending queue, snapshot
 *       the chat-history size for later notification diffing, and
 *       submit a drain task on the process's lane. Returns
 *       immediately so further inbound frames (notably
 *       {@code client-tool-result}) can flow in concurrently.</li>
 *   <li><i>Lane thread</i> — call
 *       {@link ProcessEventEmitter#runTurnNow} which drives the
 *       engine's {@code runTurn}; the engine drains the inbox itself
 *       (default impl loops drain-then-{@code steer} until empty,
 *       orchestrators like Arthur fold the whole inbox into one LLM
 *       round-trip). After the turn, ship every chat message that
 *       landed since the snapshot as a
 *       {@link MessageType#CHAT_MESSAGE_APPENDED} notification, then
 *       send the {@code process-steer} ack.</li>
 * </ol>
 *
 * <p><b>Why a queue.</b> The handler used to call
 * {@code engine.steer} directly inside the lane task. Routing through
 * the queue:
 * <ul>
 *   <li>survives crashes — a steer that arrived right before a brain
 *       restart is replayed on resume;</li>
 *   <li>unifies the path with parent-wakeup and engine-driven
 *       {@code notifyParent} — one drain-loop, no per-source
 *       branches;</li>
 *   <li>makes Auto-Wakeup free — messages that arrive during
 *       {@code engine.steer} fall into the freshly-emptied queue and
 *       are picked up by the next loop pass, no manual rescheduling.</li>
 * </ul>
 */
@Component
@Slf4j
public class ProcessSteerHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final SessionService sessionService;
    private final SessionConnectionRegistry connectionRegistry;
    private final LaneScheduler laneScheduler;
    private final ProcessEventEmitter eventEmitter;
    private final RequestAuthority authority;

    public ProcessSteerHandler(
            ObjectMapper objectMapper,
            WebSocketSender sender,
            ThinkProcessService thinkProcessService,
            ChatMessageService chatMessageService,
            SessionService sessionService,
            SessionConnectionRegistry connectionRegistry,
            LaneScheduler laneScheduler,
            ProcessEventEmitter eventEmitter,
            RequestAuthority authority) {
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.thinkProcessService = thinkProcessService;
        this.chatMessageService = chatMessageService;
        this.sessionService = sessionService;
        this.connectionRegistry = connectionRegistry;
        this.laneScheduler = laneScheduler;
        this.eventEmitter = eventEmitter;
        this.authority = authority;
    }

    @Override
    public String type() {
        return MessageType.PROCESS_STEER;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessSteerRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessSteerRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid process-steer payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getProcessName()) || isBlank(request.getContent())) {
            sender.sendError(wsSession, envelope, 400, "processName and content are required");
            return;
        }

        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }

        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findByName(tenantId, sessionId, request.getProcessName());
        if (processOpt.isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Think-process '" + request.getProcessName() + "' not found in session '"
                            + sessionId + "'");
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        String processId = process.getId();
        authority.enforce(ctx,
                new Resource.ThinkProcess(process.getTenantId(), process.getProjectId(),
                        process.getSessionId(), processId == null ? "" : processId),
                Action.EXECUTE);

        // Multi-user routing — see planning/multi-user-sessions.md §3.3.
        // Collab is active when the session permits multiple clients
        // AND more than one is currently bound. Mention status follows
        // the simple parser (no code-block awareness in v1).
        boolean collabActive = isCollabActive(sessionId);
        boolean addressedToAgent =
                !collabActive || ChatMentionParser.isAddressedToAgent(request.getContent());

        if (!addressedToAgent) {
            // Background turn — persist into chat history for context
            // but do not wake the engine. Broadcast happens via the
            // existing ChatMessageNotificationDispatcher listener on
            // ChatMessageAppendedEvent.
            chatMessageService.append(ChatMessageDocument.builder()
                    .tenantId(tenantId)
                    .sessionId(sessionId)
                    .thinkProcessId(processId)
                    .role(ChatRole.USER)
                    .content(request.getContent())
                    .senderUserId(ctx.getUserId())
                    .senderDisplayName(ctx.getDisplayName())
                    .addressedToAgent(false)
                    .build());
            // Ack the steer immediately — the agent did not run, so
            // there's no status change to report. Status returned is
            // the current (unchanged) one for symmetry with the
            // addressed reply.
            ProcessSteerResponse response = ProcessSteerResponse.builder()
                    .thinkProcessId(processId)
                    .processName(request.getProcessName())
                    .status(process.getStatus())
                    .build();
            sender.sendReply(wsSession, envelope, MessageType.PROCESS_STEER, response);
            return;
        }

        // Auto-resume on incoming user input. The user paused, the
        // chat went PAUSED, and now they're sending the correction.
        // Without this flip, the message would land in the queue but
        // the lane wouldn't drain (status-gated). User-typed input is
        // implicitly a "continue" signal.
        boolean wasResumed = false;
        if (process.getStatus() == ThinkProcessStatus.PAUSED) {
            log.info("Auto-resume on user steer: process='{}' PAUSED -> IDLE",
                    request.getProcessName());
            thinkProcessService.updateStatus(processId, ThinkProcessStatus.IDLE);
            thinkProcessService.clearHalt(processId);
            wasResumed = true;
        }

        // If we just auto-resumed, prepend a short system note to the
        // user's message so the chat-engine (Arthur) knows the user
        // paused before this message and which workers are currently
        // halted — without this, Arthur replies from his unchanged
        // chat-history and tends to hallucinate that paused workers
        // are still running.
        StringBuilder enriched = new StringBuilder();
        if (wasResumed) {
            enriched.append(buildResumeContext(tenantId, sessionId, processId));
        }
        String ideBlock = renderIdeContext(request.getIdeContext());
        if (!ideBlock.isEmpty()) {
            enriched.append(ideBlock);
        }
        enriched.append(request.getContent());
        String content = enriched.toString();

        SteerMessage.UserChatInput userInput = new SteerMessage.UserChatInput(
                Instant.now(),
                request.getIdempotencyKey(),
                ctx.getUserId(),
                ctx.getDisplayName(),
                content,
                request.getAttachments() == null ? java.util.List.of() : request.getAttachments(),
                Boolean.TRUE.equals(request.getVoiceMode()));
        PendingMessageDocument doc = SteerMessageCodec.toDocument(userInput);

        if (!thinkProcessService.appendPending(processId, doc)) {
            sender.sendError(wsSession, envelope, 404,
                    "Think-process '" + request.getProcessName() + "' disappeared before steer");
            return;
        }

        // Snapshot before lane work so the appended-notification diff
        // doesn't include messages that already lived in the log.
        int beforeSize = chatMessageService.history(
                tenantId, sessionId, processId).size();

        laneScheduler.submit(processId, () -> runLaneTurn(
                wsSession, envelope, processId, request.getProcessName(),
                tenantId, sessionId, beforeSize));
    }

    /**
     * Drain the inbox, then ship the chat-message-appended diff and
     * the {@code process-steer} ack. Runs on the process's lane —
     * {@link LaneScheduler} guarantees serial ordering across
     * concurrent steers targeting the same process.
     */
    private void runLaneTurn(
            WebSocketSession wsSession,
            WebSocketEnvelope envelope,
            String processId,
            String processName,
            String tenantId,
            String sessionId,
            int beforeSize) {
        try {
            eventEmitter.runTurnNow(processId);
        } catch (RuntimeException e) {
            log.error("Steer drain failed id='{}': {}", processId, e.toString(), e);
            try {
                sender.sendError(wsSession, envelope, 500,
                        "Engine steer failed: " + e.getMessage());
            } catch (IOException sendErr) {
                log.warn("Failed to send error reply: {}", sendErr.toString());
            }
            return;
        }

        try {
            ThinkProcessDocument refreshed = thinkProcessService.findById(processId)
                    .orElse(null);
            // CHAT_MESSAGE_APPENDED frames for the chat-messages produced
            // by this turn are pushed by ChatMessageNotificationDispatcher
            // (Spring listener on ChatMessageAppendedEvent). Doing it
            // here too would duplicate every frame; the listener also
            // covers chat-messages produced by Auto-Wakeup turns later
            // on, which this synchronous path would miss.
            ProcessSteerResponse response = ProcessSteerResponse.builder()
                    .thinkProcessId(processId)
                    .processName(processName)
                    .status(refreshed == null ? null : refreshed.getStatus())
                    .build();
            sender.sendReply(wsSession, envelope, MessageType.PROCESS_STEER, response);
        } catch (IOException sendErr) {
            log.warn("Failed to ship steer follow-up frames: {}", sendErr.toString());
        }
    }

    /**
     * Builds a short, system-style preamble that gets prepended to
     * the user's content when the chat-process just auto-resumed
     * from PAUSED. Lists the currently paused workers so the
     * orchestrator (Arthur) doesn't hallucinate that they're still
     * running. The note is part of the same USER chat message — no
     * separate role injection — so it survives the chat history
     * intact and the LLM sees it on every subsequent turn.
     */
    private String buildResumeContext(String tenantId, String sessionId, String chatProcessId) {
        java.util.List<de.mhus.vance.shared.thinkprocess.ThinkProcessDocument> all =
                thinkProcessService.findBySession(tenantId, sessionId);
        java.util.List<String> paused = new java.util.ArrayList<>();
        java.util.List<String> closed = new java.util.ArrayList<>();
        for (de.mhus.vance.shared.thinkprocess.ThinkProcessDocument p : all) {
            if (!chatProcessId.equals(p.getParentProcessId())) continue;
            String name = p.getName();
            if (name == null) continue;
            de.mhus.vance.api.thinkprocess.ThinkProcessStatus s = p.getStatus();
            if (s == de.mhus.vance.api.thinkprocess.ThinkProcessStatus.PAUSED) {
                paused.add(name);
            } else if (s == de.mhus.vance.api.thinkprocess.ThinkProcessStatus.CLOSED) {
                closed.add(name);
            }
        }
        // The user paused YOU (this process) — pause is a course-correction
        // signal, not "more of the same please". The previous tool-batch
        // / planning step was interrupted; the new message below should be
        // treated as redirect/correction, not a continuation of whatever
        // strategy was running.
        StringBuilder b = new StringBuilder();
        b.append("[system: USER INTERRUPTED — RECONSIDER. The user pressed pause on this process ");
        b.append("before sending the message below. Treat the new message as a correction or ");
        b.append("redirect of your current direction, not as an additional task on top of what ");
        b.append("you were doing. Any in-flight tool calls were cancelled. If you were stuck in a ");
        b.append("retry loop or going down the wrong path, this is where you stop and re-plan");
        if (!paused.isEmpty()) {
            b.append("; child workers currently PAUSED: ").append(paused);
            b.append(" — call process_resume to wake one before steering it");
        }
        if (!closed.isEmpty()) {
            b.append("; child workers already CLOSED: ").append(closed);
            b.append(" — you cannot reach them anymore, spawn fresh ones with process_create");
        }
        b.append(".]\n\n");
        return b.toString();
    }

    /**
     * Renders the IDE-bridge metadata as a tag block prepended to the
     * user's chat content, so the LLM sees what file/range the user
     * is looking at without the foot having to send the buffer. The
     * tags are the contract — engines that wire IDE-tools (Step 2)
     * resolve them via {@code ide_get_selection}/{@code ide_read_buffer}
     * on demand. Empty string when nothing to render.
     */
    static String renderIdeContext(@Nullable IdeContext ideContext) {
        if (ideContext == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendRange(sb, "ide-at-mention", ideContext.getAtMention());
        appendRange(sb, "ide-selection", ideContext.getCurrentSelection());
        if (sb.length() > 0) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendRange(StringBuilder sb, String tag,
                                    @Nullable IdeFileRange range) {
        if (range == null || isBlank(range.getFilePath())) {
            return;
        }
        sb.append('<').append(tag).append(" file=\"")
                .append(escapeAttr(range.getFilePath())).append('"');
        if (range.getLineStart() != null) {
            sb.append(" lineStart=\"").append(range.getLineStart()).append('"');
        }
        if (range.getLineEnd() != null) {
            sb.append(" lineEnd=\"").append(range.getLineEnd()).append('"');
        }
        sb.append("/>\n");
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static ChatMessageAppendedData toDto(ChatMessageDocument doc, String processName) {
        return ChatMessageAppendedData.builder()
                .chatMessageId(doc.getId())
                .thinkProcessId(doc.getThinkProcessId())
                .processName(processName)
                .role(doc.getRole())
                .content(doc.getContent())
                .createdAt(doc.getCreatedAt())
                .senderUserId(doc.getSenderUserId())
                .senderDisplayName(doc.getSenderDisplayName())
                .addressedToAgent(doc.isAddressedToAgent())
                .build();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /**
     * {@code true} when the session has {@code allowMultipleClients}
     * set <em>and</em> more than one client is currently bound — the
     * runtime condition for mention-based agent routing
     * (see {@code planning/multi-user-sessions.md} §3.3). When the
     * session is private or only one client is bound, all USER turns
     * remain implicitly addressed to the agent — backwards compatible
     * with the 1:1 flow.
     */
    private boolean isCollabActive(String sessionId) {
        SessionDocument session = sessionService.findBySessionId(sessionId).orElse(null);
        if (session == null || !session.isAllowMultipleClients()) {
            return false;
        }
        return connectionRegistry.connectionCount(sessionId) > 1;
    }
}
