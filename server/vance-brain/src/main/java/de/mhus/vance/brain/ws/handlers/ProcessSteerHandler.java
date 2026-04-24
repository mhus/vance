package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers a user chat message to a named think-process, runs the engine's
 * {@code steer} synchronously, and pushes every chat message appended
 * during the turn back to the client as
 * {@link MessageType#CHAT_MESSAGE_APPENDED} notifications.
 *
 * <p>v1 is synchronous on purpose — the lane scheduler hasn't landed yet.
 * This keeps the path end-to-end testable today: the client sees the ack,
 * then one or more appended-message notifications arrive as the engine
 * persists them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessSteerHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final ChatMessageService chatMessageService;

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

        // Snapshot history size so we can emit only the newly-appended messages.
        int beforeSize = chatMessageService.history(
                tenantId, sessionId, process.getId()).size();

        SteerMessage.UserChatInput userInput = new SteerMessage.UserChatInput(
                Instant.now(),
                request.getIdempotencyKey(),
                ctx.getUserId(),
                request.getContent());
        try {
            thinkEngineService.steer(process, userInput);
        } catch (RuntimeException e) {
            log.error("Engine steer failed for process id='{}' engine='{}'",
                    process.getId(), process.getThinkEngine(), e);
            sender.sendError(wsSession, envelope, 500, "Engine steer failed: " + e.getMessage());
            return;
        }

        ThinkProcessDocument refreshed = thinkProcessService.findById(process.getId()).orElse(process);
        List<ChatMessageDocument> full = chatMessageService.history(
                tenantId, sessionId, process.getId());
        for (ChatMessageDocument appended : full.subList(beforeSize, full.size())) {
            sender.sendNotification(wsSession, MessageType.CHAT_MESSAGE_APPENDED,
                    toDto(appended, request.getProcessName()));
        }

        ProcessSteerResponse response = ProcessSteerResponse.builder()
                .thinkProcessId(refreshed.getId())
                .processName(refreshed.getName())
                .status(refreshed.getStatus())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_STEER, response);
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
