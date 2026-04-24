package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.thinkprocess.ProcessCreateRequest;
import de.mhus.vance.api.thinkprocess.ProcessCreateResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates a think-process in the caller's bound session and kicks off its
 * lifecycle via {@link ThinkEngine#start}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessCreateHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;

    @Override
    public String type() {
        return MessageType.PROCESS_CREATE;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessCreateRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessCreateRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid process-create payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getEngine()) || isBlank(request.getName())) {
            sender.sendError(wsSession, envelope, 400, "engine and name are required");
            return;
        }

        Optional<ThinkEngine> engineOpt = thinkEngineService.resolve(request.getEngine());
        if (engineOpt.isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Unknown think-engine '" + request.getEngine()
                            + "' — registered: " + thinkEngineService.listEngines());
            return;
        }
        ThinkEngine engine = engineOpt.get();

        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }

        ThinkProcessDocument created;
        try {
            created = thinkProcessService.create(
                    tenantId,
                    sessionId,
                    request.getName(),
                    engine.name(),
                    engine.version(),
                    request.getTitle(),
                    request.getGoal());
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException e) {
            sender.sendError(wsSession, envelope, 409, e.getMessage());
            return;
        }

        try {
            thinkEngineService.start(created);
        } catch (RuntimeException e) {
            log.error("Engine start failed for process id='{}' engine='{}'",
                    created.getId(), engine.name(), e);
            sender.sendError(wsSession, envelope, 500,
                    "Engine start failed: " + e.getMessage());
            return;
        }

        ThinkProcessDocument refreshed = thinkProcessService.findById(created.getId())
                .orElse(created);
        ProcessCreateResponse response = ProcessCreateResponse.builder()
                .thinkProcessId(refreshed.getId())
                .name(refreshed.getName())
                .engine(refreshed.getThinkEngine())
                .status(refreshed.getStatus())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_CREATE, response);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
