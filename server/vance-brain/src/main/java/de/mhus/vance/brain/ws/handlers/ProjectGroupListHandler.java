package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectGroupListResponse;
import de.mhus.vance.api.ws.ProjectGroupSummary;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.projectgroup.ProjectGroupDocument;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Lists project groups visible in the caller's tenant. Allowed with or without
 * a bound session. No request payload is required.
 */
@Component
@RequiredArgsConstructor
public class ProjectGroupListHandler implements WsHandler {

    private final WebSocketSender sender;
    private final ProjectGroupService projectGroupService;

    @Override
    public String type() {
        return MessageType.PROJECTGROUP_LIST;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return true;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        List<ProjectGroupDocument> documents = projectGroupService.all(ctx.getTenantId());
        List<ProjectGroupSummary> summaries =
                documents.stream().map(ProjectGroupListHandler::toSummary).toList();
        ProjectGroupListResponse response =
                ProjectGroupListResponse.builder().groups(summaries).build();
        sender.sendReply(wsSession, envelope, MessageType.PROJECTGROUP_LIST, response);
    }

    private static ProjectGroupSummary toSummary(ProjectGroupDocument doc) {
        return ProjectGroupSummary.builder()
                .name(doc.getName())
                .title(doc.getTitle())
                .enabled(doc.isEnabled())
                .build();
    }
}
