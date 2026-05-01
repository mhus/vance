package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
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
    private final LaneScheduler laneScheduler;
    private final ProcessEventEmitter eventEmitter;

    public ProcessSteerHandler(
            ObjectMapper objectMapper,
            WebSocketSender sender,
            ThinkProcessService thinkProcessService,
            ChatMessageService chatMessageService,
            LaneScheduler laneScheduler,
            ProcessEventEmitter eventEmitter) {
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.thinkProcessService = thinkProcessService;
        this.chatMessageService = chatMessageService;
        this.laneScheduler = laneScheduler;
        this.eventEmitter = eventEmitter;
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
        String content = wasResumed
                ? buildResumeContext(tenantId, sessionId, processId) + request.getContent()
                : request.getContent();

        SteerMessage.UserChatInput userInput = new SteerMessage.UserChatInput(
                Instant.now(),
                request.getIdempotencyKey(),
                ctx.getUserId(),
                content);
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
            List<ChatMessageDocument> full = chatMessageService.history(
                    tenantId, sessionId, processId);
            for (ChatMessageDocument appended : full.subList(beforeSize, full.size())) {
                if (appended.getRole() == ChatRole.USER) {
                    // Sender's own echo — client renders locally.
                    continue;
                }
                sender.sendNotification(wsSession, MessageType.CHAT_MESSAGE_APPENDED,
                        toDto(appended, processName));
            }
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
        StringBuilder b = new StringBuilder();
        b.append("[system: the user paused this session before sending this message");
        if (!paused.isEmpty()) {
            b.append("; workers currently PAUSED: ").append(paused);
            b.append(" — call process_resume to wake one before steering it");
        }
        if (!closed.isEmpty()) {
            b.append("; workers already CLOSED: ").append(closed);
            b.append(" — you cannot reach them anymore, spawn fresh ones with process_create");
        }
        b.append(". Call process_list if unsure of current state.]\n\n");
        return b.toString();
    }

    private static ChatMessageAppendedData toDto(ChatMessageDocument doc, String processName) {
        return ChatMessageAppendedData.builder()
                .chatMessageId(doc.getId())
                .thinkProcessId(doc.getThinkProcessId())
                .processName(processName)
                .role(doc.getRole())
                .content(doc.getContent())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
