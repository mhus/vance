package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
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

        SteerMessage.UserChatInput userInput = new SteerMessage.UserChatInput(
                Instant.now(),
                request.getIdempotencyKey(),
                ctx.getUserId(),
                request.getContent());
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
