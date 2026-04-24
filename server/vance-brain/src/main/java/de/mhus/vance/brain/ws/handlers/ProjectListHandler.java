package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectListRequest;
import de.mhus.vance.api.ws.ProjectListResponse;
import de.mhus.vance.api.ws.ProjectSummary;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists projects visible in the caller's tenant. Optional
 * {@code projectGroupId} filter narrows the result to a single group. Allowed
 * with or without a bound session.
 */
@Component
@RequiredArgsConstructor
public class ProjectListHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ProjectService projectService;

    @Override
    public String type() {
        return MessageType.PROJECT_LIST;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return true;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        String projectGroupId = null;
        if (envelope.getData() != null) {
            try {
                ProjectListRequest request =
                        objectMapper.convertValue(envelope.getData(), ProjectListRequest.class);
                if (request != null) {
                    projectGroupId = request.getProjectGroupId();
                }
            } catch (IllegalArgumentException e) {
                sender.sendError(wsSession, envelope, 400,
                        "Invalid project.list payload: " + e.getMessage());
                return;
            }
        }

        List<ProjectDocument> documents = isBlank(projectGroupId)
                ? projectService.all(ctx.getTenantId())
                : projectService.byGroup(ctx.getTenantId(), projectGroupId);

        List<ProjectSummary> summaries = documents.stream().map(ProjectListHandler::toSummary).toList();
        ProjectListResponse response = ProjectListResponse.builder().projects(summaries).build();
        sender.sendReply(wsSession, envelope, MessageType.PROJECT_LIST, response);
    }

    private static ProjectSummary toSummary(ProjectDocument doc) {
        return ProjectSummary.builder()
                .name(doc.getName())
                .title(doc.getTitle())
                .projectGroupId(doc.getProjectGroupId())
                .enabled(doc.isEnabled())
                .build();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
