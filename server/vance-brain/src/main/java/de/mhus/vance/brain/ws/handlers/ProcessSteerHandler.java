package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
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
 * Delivers a user chat message to a named think-process, runs the
 * engine's {@code steer}, and pushes every chat message appended
 * during the turn back to the client as
 * {@link MessageType#CHAT_MESSAGE_APPENDED} notifications.
 *
 * <p><b>Async dispatch:</b> the engine call runs on a virtual-thread
 * executor, not on the WebSocket receive thread. Spring serialises
 * inbound frames per session, so a synchronous engine call would
 * <em>block</em> the receive thread — and any message the engine is
 * waiting for (e.g. a {@code client-tool-result} during a client-tool
 * loop) would queue behind the very thread waiting for it. Self-lock,
 * timeouts, no-pending warnings. Dispatching to a separate executor
 * keeps the receive thread idle so reply frames can land in time.
 *
 * <p>The steer-reply ({@code process-steer} ack) is sent from the
 * worker once the engine completes — the foot's
 * {@code connection.request} blocks until that arrives, so timing-wise
 * the experience is identical to the synchronous path it replaces.
 */
@Component
@Slf4j
public class ProcessSteerHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final ChatMessageService chatMessageService;
    private final LaneScheduler laneScheduler;

    public ProcessSteerHandler(
            ObjectMapper objectMapper,
            WebSocketSender sender,
            ThinkProcessService thinkProcessService,
            ThinkEngineService thinkEngineService,
            ChatMessageService chatMessageService,
            LaneScheduler laneScheduler) {
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.thinkProcessService = thinkProcessService;
        this.thinkEngineService = thinkEngineService;
        this.chatMessageService = chatMessageService;
        this.laneScheduler = laneScheduler;
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

        // Snapshot before any engine work — must read on the receive thread
        // because subsequent inbound frames need to flow concurrently.
        int beforeSize = chatMessageService.history(
                tenantId, sessionId, process.getId()).size();

        SteerMessage.UserChatInput userInput = new SteerMessage.UserChatInput(
                Instant.now(),
                request.getIdempotencyKey(),
                ctx.getUserId(),
                request.getContent());

        // Hand the engine work off and free the receive thread. The
        // LaneScheduler serialises submissions on this process id, so two
        // concurrent steer messages targeting the same process can't race
        // on the engine's mutable state.
        laneScheduler.submit(process.getId(), () -> runSteerAsync(
                wsSession, envelope, process, request, userInput,
                tenantId, sessionId, beforeSize));
    }

    /**
     * Engine + push + reply, executed off the WS receive thread. All
     * outbound writes go through {@link WebSocketSender}, which
     * serialises sends per session — so a streaming chunk fired from
     * a langchain4j callback thread can't interleave with the appended
     * notifications produced here.
     */
    private void runSteerAsync(
            WebSocketSession wsSession,
            WebSocketEnvelope envelope,
            ThinkProcessDocument process,
            ProcessSteerRequest request,
            SteerMessage.UserChatInput userInput,
            String tenantId,
            String sessionId,
            int beforeSize) {
        try {
            thinkEngineService.steer(process, userInput);
        } catch (RuntimeException e) {
            log.error("Engine steer failed for process id='{}' engine='{}'",
                    process.getId(), process.getThinkEngine(), e);
            try {
                sender.sendError(wsSession, envelope, 500,
                        "Engine steer failed: " + e.getMessage());
            } catch (IOException sendErr) {
                log.warn("Failed to send error reply: {}", sendErr.toString());
            }
            return;
        }

        try {
            ThinkProcessDocument refreshed = thinkProcessService.findById(process.getId())
                    .orElse(process);
            List<ChatMessageDocument> full = chatMessageService.history(
                    tenantId, sessionId, process.getId());
            for (ChatMessageDocument appended : full.subList(beforeSize, full.size())) {
                // Skip USER echoes — the sending client already knows what it
                // just typed and renders it locally.
                if (appended.getRole() == ChatRole.USER) {
                    continue;
                }
                sender.sendNotification(wsSession, MessageType.CHAT_MESSAGE_APPENDED,
                        toDto(appended, request.getProcessName()));
            }
            ProcessSteerResponse response = ProcessSteerResponse.builder()
                    .thinkProcessId(refreshed.getId())
                    .processName(refreshed.getName())
                    .status(refreshed.getStatus())
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
