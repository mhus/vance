package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.command.ProcessCommandRequest;
import de.mhus.vance.api.command.ProcessCommandResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandDispatcher;
import de.mhus.vance.brain.command.EngineCommandResult;
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
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound {@code process-command} → engine-command dispatch. The
 * {@code //verb} client surface for direct, control-plane commands to a
 * think-process's engine.
 *
 * <p>Two phases, mirroring {@link ProcessSteerHandler}:
 * <ol>
 *   <li><i>Receive thread</i> — validate, resolve the target process,
 *       enforce {@code EXECUTE}, then submit the dispatch on the
 *       process's lane and return.</li>
 *   <li><i>Lane thread</i> — re-read the process fresh (serialized with
 *       any in-flight turn), run
 *       {@link EngineCommandDispatcher#dispatch}, and reply with the
 *       {@link ProcessCommandResponse}.</li>
 * </ol>
 *
 * <p>Unlike {@code process-steer} this does <b>not</b> trigger an LLM
 * turn: an unknown verb is a defined no-op, a known verb runs its
 * handler. See {@code planning/engine-commands.md} §2.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessCommandHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;
    private final EngineCommandDispatcher dispatcher;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.PROCESS_COMMAND;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessCommandRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessCommandRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid process-command payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getProcessName()) || isBlank(request.getCommand())) {
            sender.sendError(wsSession, envelope, 400, "processName and command are required");
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

        String verb = request.getCommand();
        Map<String, Object> params =
                request.getParams() == null ? Map.of() : request.getParams();

        laneScheduler.submit(processId, () -> runLaneDispatch(
                wsSession, envelope, processId, request.getProcessName(), verb, params));
    }

    /**
     * Runs on the process's lane — {@link LaneScheduler} serializes this
     * against concurrent steers/commands targeting the same process, so
     * the dispatch never races an in-flight turn.
     */
    private void runLaneDispatch(
            WebSocketSession wsSession,
            WebSocketEnvelope envelope,
            @Nullable String processId,
            String processName,
            String verb,
            Map<String, Object> params) {
        ThinkProcessDocument fresh = processId == null
                ? null
                : thinkProcessService.findById(processId).orElse(null);
        if (fresh == null) {
            try {
                sender.sendError(wsSession, envelope, 404,
                        "Think-process '" + processName + "' disappeared before command dispatch");
            } catch (IOException e) {
                log.warn("Failed to send process-command 404: {}", e.toString());
            }
            return;
        }

        EngineCommandResult result;
        try {
            result = dispatcher.dispatch(fresh, new EngineCommand(verb, params));
        } catch (RuntimeException e) {
            // Dispatcher is contractually no-throw, but stay defensive so
            // a bug there can't wedge the lane task without a reply.
            log.error("process-command dispatch threw for verb='{}' id='{}': {}",
                    verb, processId, e.toString(), e);
            result = EngineCommandResult.error("Dispatch failed: " + e.getMessage());
        }

        try {
            ProcessCommandResponse response = ProcessCommandResponse.builder()
                    .processName(processName)
                    .command(verb)
                    .outcome(result.outcome())
                    .message(result.message())
                    .value(result.value())
                    .build();
            sender.sendReply(wsSession, envelope, MessageType.PROCESS_COMMAND, response);
        } catch (IOException e) {
            log.warn("Failed to ship process-command reply id='{}': {}", processId, e.toString());
        }
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
