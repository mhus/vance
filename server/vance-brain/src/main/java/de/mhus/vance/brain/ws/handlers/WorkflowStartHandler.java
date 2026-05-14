package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.hactar.HactarWorkflowStartRequest;
import de.mhus.vance.api.hactar.HactarWorkflowStartResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.hactar.HactarWorkflowService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.hactar.HactarWorkflowParseException;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * WS counterpart of the REST {@code POST workflows/{name}/start}.
 * Starts a workflow in the bound session's project — same semantics
 * as the REST and the {@code workflow_start} agent tool.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class WorkflowStartHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final HactarWorkflowService workflowService;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.WORKFLOW_START;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        HactarWorkflowStartRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), HactarWorkflowStartRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid workflow-start payload: " + e.getMessage());
            return;
        }
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            sender.sendError(wsSession, envelope, 400, "name is required");
            return;
        }
        String projectId = ctx.getProjectId();
        if (projectId == null) {
            sender.sendError(wsSession, envelope, 400,
                    "workflow-start requires a session bound to a project");
            return;
        }
        authority.enforce(ctx,
                new Resource.Project(ctx.getTenantId(), projectId), Action.WRITE);

        String startedBy = request.getStartedBy() != null && !request.getStartedBy().isBlank()
                ? request.getStartedBy()
                : ctx.getUserId();

        String runId;
        try {
            runId = workflowService.start(
                    ctx.getTenantId(),
                    projectId,
                    request.getName(),
                    request.getParams(),
                    startedBy);
        } catch (HactarWorkflowService.HactarWorkflowException e) {
            sender.sendError(wsSession, envelope, 404, e.getMessage());
            return;
        } catch (HactarWorkflowParseException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Workflow YAML invalid: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.warn("workflow-start failed name='{}': {}", request.getName(), e.toString());
            sender.sendError(wsSession, envelope, 500,
                    "Workflow start failed: " + e.getMessage());
            return;
        }

        HactarWorkflowStartResponse response = HactarWorkflowStartResponse.builder()
                .workflowRunId(runId)
                .workflowName(request.getName())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.WORKFLOW_START, response);
    }
}
