package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.thinkprocess.ProcessInboxRequest;
import de.mhus.vance.api.thinkprocess.ProcessInboxResponse;
import de.mhus.vance.api.thinkprocess.QueuedMessageEntry;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.enginemessage.QueuedMessageEntryMapper;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the not-yet-drained queue of one think-process of the bound
 * session — the authoritative read behind the "queued messages" display
 * ("active message queue", see {@code planning/active-message-queue.md}
 * §4 P3).
 *
 * <p>Why a read at all when the {@code process-queue} notification pushes
 * changes: that push is optimistic (dropped when no client is bound) and
 * only carries the delta. A client that reconnects, joins a multi-user
 * session late, or simply missed a frame rebuilds its queue view from
 * here — the queue itself lives in {@code engine_messages} and survives
 * everything the client does not.
 *
 * <p>Session-scoped by construction, mirroring
 * {@link ProcessMessagesHandler}: the process is resolved inside the bound
 * session and an id from another session yields 404 rather than someone
 * else's queue.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessInboxHandler implements WsHandler {

    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.PROCESS_INBOX;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }
        ProcessInboxRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessInboxRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid process-inbox payload: " + e.getMessage());
            return;
        }
        if (request == null || (isBlank(request.getName()) && isBlank(request.getProcessId()))) {
            sender.sendError(wsSession, envelope, 400, "process-inbox requires 'name' or 'processId'");
            return;
        }
        authority.enforce(
                ctx,
                new Resource.Session(
                        ctx.getTenantId(), ctx.getProjectId() == null ? "" : ctx.getProjectId(), sessionId),
                Action.READ);

        Optional<ThinkProcessDocument> found = resolve(ctx.getTenantId(), sessionId, request);
        if (found.isEmpty()) {
            sender.sendError(
                    wsSession, envelope, 404, "Process '" + describeTarget(request) + "' not found in this session");
            return;
        }
        ThinkProcessDocument process = found.get();

        List<PendingMessageDocument> queued = thinkProcessService.listPending(process.getId());
        List<QueuedMessageEntry> entries = QueuedMessageEntryMapper.fromPending(queued);
        ProcessInboxResponse response = ProcessInboxResponse.builder()
                .thinkProcessId(process.getId())
                .processName(process.getName())
                .messages(entries)
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_INBOX, response);
    }

    /**
     * Resolve inside the bound session only — this is where the session
     * scope is enforced. Mirrors {@link ProcessMessagesHandler#resolve}.
     */
    private Optional<ThinkProcessDocument> resolve(String tenantId, String sessionId, ProcessInboxRequest request) {
        String name = request.getName();
        if (!isBlank(name)) {
            return thinkProcessService.findByName(tenantId, sessionId, name);
        }
        String processId = request.getProcessId();
        if (isBlank(processId)) {
            return Optional.empty();
        }
        return thinkProcessService
                .findById(processId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .filter(p -> sessionId.equals(p.getSessionId()));
    }

    private static String describeTarget(ProcessInboxRequest request) {
        String name = request.getName();
        if (!isBlank(name)) {
            return name;
        }
        String processId = request.getProcessId();
        return processId == null ? "?" : processId;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
