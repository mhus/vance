package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.thinkprocess.ProcessCompactRequest;
import de.mhus.vance.api.thinkprocess.ProcessCompactResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Manual memory-compaction trigger for a named think-process. Same
 * underlying {@link MemoryCompactionService} the engine uses on its
 * automatic threshold — exposing it here lets clients run compaction
 * proactively (e.g. before a long context-heavy turn).
 *
 * <p>The reply mirrors {@link CompactionResult}; on a no-op the
 * {@code compacted} flag is {@code false} and {@code reason} carries
 * the explanation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessCompactHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final MemoryCompactionService compactionService;
    private final LaneScheduler laneScheduler;
    private final RequestAuthority authority;

    /** Bound wait for the lane-scheduled compaction — matches foot's 60s
     *  request timeout. On timeout the compaction stays queued on the lane. */
    private static final long COMPACT_TIMEOUT_MS = 60_000L;

    @Override
    public String type() {
        return MessageType.PROCESS_COMPACT;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessCompactRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessCompactRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid process-compact payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getProcessName())) {
            sender.sendError(wsSession, envelope, 400, "processName is required");
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
        authority.enforce(ctx,
                new Resource.ThinkProcess(process.getTenantId(), process.getProjectId(),
                        process.getSessionId(), process.getId() == null ? "" : process.getId()),
                Action.EXECUTE);

        // Run on the process lane so compaction serializes with turns and
        // can never mutate the chat history out from under an in-flight
        // prompt/anchor. On timeout it stays queued and completes between
        // turns; we reply with a deferred no-op.
        CompactionResult result;
        try {
            result = laneScheduler
                    .submit(process.getId(), () -> compactionService.compact(process))
                    .get(COMPACT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            result = CompactionResult.noop("deferred");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sender.sendError(wsSession, envelope, 500, "Interrupted while compacting");
            return;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("process-compact failed for tenant='{}' session='{}' process='{}'",
                    tenantId, sessionId, request.getProcessName(), cause);
            sender.sendError(wsSession, envelope, 500,
                    "Compaction failed: " + cause.getMessage());
            return;
        }

        ProcessCompactResponse response = ProcessCompactResponse.builder()
                .processName(request.getProcessName())
                .compacted(result.compacted())
                .messagesCompacted(result.messagesCompacted())
                .summaryChars(result.summaryChars())
                .memoryId(result.memoryId())
                .supersededMemoryId(result.supersededMemoryId())
                .reason(result.reason())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_COMPACT, response);
    }

    private static boolean isBlank(@org.jspecify.annotations.Nullable String s) {
        return s == null || s.isBlank();
    }
}
