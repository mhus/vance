package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageDto;
import de.mhus.vance.api.thinkprocess.ProcessMessagesRequest;
import de.mhus.vance.api.thinkprocess.ProcessMessagesResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageDtoMapper;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the conversation of a single think-process of the bound session —
 * the backing call for a client's process detail view ("what is that worker
 * doing?"). Both foot's {@code /ui-process} and the web process panel use
 * this.
 *
 * <p><b>Why a WS handler and not the existing REST endpoint.</b>
 * {@code GET /brain/{tenant}/process/{id}/messages} authorises on
 * {@code Resource.Project} + {@code READ} on purpose: it also serves
 * chatless Damogran agents that live in system sessions and have no user
 * owner. Reusing it for the detail view would silently widen the process
 * view beyond the session the client is bound to. Here the bound session is
 * the frame of reference, so the session scope decided in
 * {@code planning/process-visibility.md} §5.1 holds by construction —
 * a process of another session is simply not found.
 *
 * <p>Addressing by {@code name} is the primary path (that is what
 * {@code process-list} shows and what the user types); {@code processId} is
 * accepted as an alternative.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessMessagesHandler implements WsHandler {

    /** Newest-N default when the request doesn't cap. */
    private static final int DEFAULT_LIMIT = 200;

    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.PROCESS_MESSAGES;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }
        ProcessMessagesRequest request;
        try {
            request = objectMapper.convertValue(
                    envelope.getData(), ProcessMessagesRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid process-messages payload: " + e.getMessage());
            return;
        }
        if (request == null
                || (isBlank(request.getName()) && isBlank(request.getProcessId()))) {
            sender.sendError(wsSession, envelope, 400,
                    "process-messages requires 'name' or 'processId'");
            return;
        }
        authority.enforce(ctx,
                new Resource.Session(ctx.getTenantId(),
                        ctx.getProjectId() == null ? "" : ctx.getProjectId(), sessionId),
                Action.READ);

        Optional<ThinkProcessDocument> found = resolve(ctx.getTenantId(), sessionId, request);
        if (found.isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Process '" + describeTarget(request) + "' not found in this session");
            return;
        }
        ThinkProcessDocument process = found.get();

        List<ChatMessageDocument> messages = chatMessageService.activeHistoryWithInterim(
                ctx.getTenantId(), sessionId, process.getId());
        int limit = resolveLimit(request);
        Integer olderTruncated = null;
        if (messages.size() > limit) {
            olderTruncated = messages.size() - limit;
            messages = messages.subList(messages.size() - limit, messages.size());
        }

        ProcessMessagesResponse response = ProcessMessagesResponse.builder()
                .processId(process.getId())
                .name(process.getName())
                .thinkEngine(process.getThinkEngine())
                .status(process.getStatus())
                .closeReason(process.getCloseReason())
                .messages(messages.stream()
                        .map(doc -> toDto(doc, process.getName()))
                        .toList())
                .olderTruncated(olderTruncated)
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_MESSAGES, response);
    }

    /**
     * Resolve inside the bound session only — this is where the session
     * scope is enforced. An id from another session yields empty rather
     * than someone else's transcript.
     */
    private Optional<ThinkProcessDocument> resolve(
            String tenantId, String sessionId, ProcessMessagesRequest request) {
        String name = request.getName();
        if (!isBlank(name)) {
            return thinkProcessService.findByName(tenantId, sessionId, name);
        }
        String processId = request.getProcessId();
        if (isBlank(processId)) {
            return Optional.empty();
        }
        return thinkProcessService.findById(processId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .filter(p -> sessionId.equals(p.getSessionId()));
    }

    private static int resolveLimit(ProcessMessagesRequest request) {
        Integer limit = request.getLimit();
        return limit != null && limit > 0 ? limit : DEFAULT_LIMIT;
    }

    private static String describeTarget(ProcessMessagesRequest request) {
        String name = request.getName();
        if (!isBlank(name)) {
            return name;
        }
        String processId = request.getProcessId();
        return processId == null ? "?" : processId;
    }

    private static ChatMessageDto toDto(ChatMessageDocument doc, String processName) {
        return ChatMessageDtoMapper.toDto(doc, processName);
    }

    private static boolean isBlank(@org.jspecify.annotations.Nullable String value) {
        return value == null || value.isBlank();
    }
}
