package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.thinkprocess.ProcessListRequest;
import de.mhus.vance.api.thinkprocess.ProcessListResponse;
import de.mhus.vance.api.thinkprocess.ProcessSummary;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists every think-process in the bound session. Lightweight reply
 * shape (no engine state) — pair with {@code process-status} for
 * detail.
 *
 * <p>Terminal-state processes (STOPPED / DONE / STALE) are
 * audit-only — they're persisted for the record but hidden from
 * the live view by default. The caller can opt in with
 * {@code includeTerminated: true} on the request payload to see
 * them. The reply carries {@code hiddenTerminated} as a hint when
 * that filter dropped any rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessListHandler implements WsHandler {

    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;

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
        boolean includeTerminated = false;
        if (envelope.getData() != null) {
            try {
                ProcessListRequest req = objectMapper.convertValue(
                        envelope.getData(), ProcessListRequest.class);
                if (req != null) {
                    includeTerminated = req.isIncludeTerminated();
                }
            } catch (IllegalArgumentException e) {
                sender.sendError(wsSession, envelope, 400,
                        "Invalid process-list payload: " + e.getMessage());
                return;
            }
        }

        List<ThinkProcessDocument> docs = thinkProcessService.findBySession(
                ctx.getTenantId(), sessionId);
        List<ProcessSummary> rows = new ArrayList<>(docs.size());
        int hidden = 0;
        for (ThinkProcessDocument doc : docs) {
            if (!includeTerminated && isTerminated(doc.getStatus())) {
                hidden++;
                continue;
            }
            rows.add(toSummary(doc));
        }
        ProcessListResponse response = ProcessListResponse.builder()
                .processes(rows)
                .hiddenTerminated(hidden == 0 ? null : hidden)
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_LIST, response);
    }

    private static boolean isTerminated(ThinkProcessStatus s) {
        return s == ThinkProcessStatus.STOPPED
                || s == ThinkProcessStatus.DONE
                || s == ThinkProcessStatus.STALE;
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
