package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.thinkprocess.ProcessListResponse;
import de.mhus.vance.api.thinkprocess.ProcessSummary;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Lists every think-process in the bound session. Lightweight reply
 * shape (no engine state) — pair with {@code process-status} once
 * that exists for detail.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessListHandler implements WsHandler {

    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String type() {
        return MessageType.PROCESS_LIST;
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
        List<ThinkProcessDocument> docs = thinkProcessService.findBySession(
                ctx.getTenantId(), sessionId);
        List<ProcessSummary> rows = docs.stream().map(ProcessListHandler::toSummary).toList();
        ProcessListResponse response = ProcessListResponse.builder()
                .processes(new java.util.ArrayList<>(rows))
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_LIST, response);
    }

    static ProcessSummary toSummary(ThinkProcessDocument doc) {
        return ProcessSummary.builder()
                .id(doc.getId())
                .name(doc.getName())
                .title(doc.getTitle())
                .thinkEngine(doc.getThinkEngine())
                .thinkEngineVersion(doc.getThinkEngineVersion())
                .goal(doc.getGoal())
                .status(doc.getStatus())
                .createdAt(null) // ThinkProcessDocument has no createdAt; populate when added
                .build();
    }
}
