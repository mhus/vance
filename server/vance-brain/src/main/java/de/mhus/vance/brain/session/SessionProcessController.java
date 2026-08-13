package de.mhus.vance.brain.session;

import de.mhus.vance.api.thinkprocess.ProcessListResponse;
import de.mhus.vance.api.thinkprocess.ProcessMessagesResponse;
import de.mhus.vance.api.thinkprocess.ProcessSummary;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageDtoMapper;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessSummaryMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only think-process view of <em>any</em> session of the tenant — the
 * preview the web session picker offers per row, so "what are this session's
 * workers doing?" can be answered without binding (and thereby taking over)
 * the session.
 *
 * <p><b>Why not the WebSocket handlers.</b> {@code process-list} and
 * {@code process-messages} resolve against the <em>bound</em> session by
 * construction, which is exactly right for the in-session panel but makes
 * them unusable from the picker, where no session is bound yet. Here the
 * session is named in the path and authorised explicitly, so the same scope
 * rule holds: a process of another session is simply not found.
 *
 * <p>Steering, pause/resume and stop stay WebSocket-only on purpose. They act
 * on the process lane of the session the client is bound to; offering them
 * here would mean acting on a session nobody is holding. The preview is
 * read-only, and the way to steer is to open the session.
 */
@RestController
@RequestMapping("/brain/{tenant}/sessions/{sessionId}/processes")
@RequiredArgsConstructor
public class SessionProcessController {

    /** Newest-N default, matching the {@code process-messages} handler. */
    private static final int DEFAULT_LIMIT = 200;

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final RequestAuthority authority;

    @GetMapping
    public ProcessListResponse list(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "includeTerminated", required = false, defaultValue = "false")
                    boolean includeTerminated,
            HttpServletRequest request) {
        requireReadableSession(tenant, sessionId, request);

        List<ThinkProcessDocument> docs = thinkProcessService.findBySession(tenant, sessionId);
        List<ProcessSummary> rows = new ArrayList<>(docs.size());
        int hidden = 0;
        for (ThinkProcessDocument doc : docs) {
            if (!includeTerminated && doc.getStatus() == ThinkProcessStatus.CLOSED) {
                hidden++;
                continue;
            }
            rows.add(ThinkProcessSummaryMapper.toSummary(doc));
        }
        return ProcessListResponse.builder()
                .processes(rows)
                .hiddenTerminated(hidden == 0 ? null : hidden)
                .build();
    }

    @GetMapping("/{name}/messages")
    public ProcessMessagesResponse messages(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            @PathVariable("name") String name,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        requireReadableSession(tenant, sessionId, request);

        ThinkProcessDocument process = thinkProcessService.findByName(tenant, sessionId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Process '" + name + "' not found in session '" + sessionId + "'"));

        List<ChatMessageDocument> messages = chatMessageService.activeHistoryWithInterim(
                tenant, sessionId, process.getId());
        int cap = limit != null && limit > 0 ? limit : DEFAULT_LIMIT;
        Integer olderTruncated = null;
        if (messages.size() > cap) {
            olderTruncated = messages.size() - cap;
            messages = messages.subList(messages.size() - cap, messages.size());
        }

        return ProcessMessagesResponse.builder()
                .processId(process.getId())
                .name(process.getName())
                .thinkEngine(process.getThinkEngine())
                .status(process.getStatus())
                .closeReason(process.getCloseReason())
                .messages(messages.stream()
                        .map(doc -> ChatMessageDtoMapper.toDto(doc, process.getName()))
                        .toList())
                .olderTruncated(olderTruncated)
                .build();
    }

    /**
     * Resolve the session inside the tenant and enforce {@link Action#READ} on
     * it. A session of another tenant is a 404, not a 403 — the caller has no
     * business learning it exists.
     */
    private void requireReadableSession(
            String tenant, String sessionId, HttpServletRequest request) {
        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.READ);
    }
}
