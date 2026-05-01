package de.mhus.vance.brain.chat;

import de.mhus.vance.api.chat.ChatMessageDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pull endpoint for the persisted chat history of a session.
 *
 * <p>Returns the same messages that the LLM would replay on the next
 * roundtrip — i.e. {@link ChatMessageService#activeHistory} (messages
 * already rolled into a memory compaction are filtered out, matching
 * what {@code vance-foot} renders).
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the
 * JWT's {@code tid} claim before the request reaches this controller.
 * Per-record this controller additionally checks that the session's
 * {@code tenantId} matches the path tenant and that the session's
 * {@code userId} is the authenticated user — chat history is private.
 */
@RestController
@RequestMapping("/brain/{tenant}/sessions")
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryController {

    /**
     * Hard cap on returned messages per request. Pathological sessions
     * (compaction misconfigured, runaway loops) shouldn't blow up the
     * response. The web UI displays history scrolling-from-top so we
     * cut at the head of the list (oldest), keeping the most recent
     * {@value} messages visible.
     */
    private static final int DEFAULT_LIMIT = 500;

    private final SessionService sessionService;
    private final ChatMessageService chatMessageService;
    private final RequestAuthority authority;

    @GetMapping("/{sessionId}/messages")
    public List<ChatMessageDto> listMessages(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "limit", required = false) @Nullable Integer limit,
            HttpServletRequest request) {

        String currentUser = currentUser(request);

        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));

        if (!currentUser.equals(session.getUserId())) {
            log.debug("Chat history access denied: session='{}' owner='{}' caller='{}'",
                    sessionId, session.getUserId(), currentUser);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Session '" + sessionId + "' belongs to another user");
        }
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()), Action.READ);

        String chatProcessId = session.getChatProcessId();
        if (chatProcessId == null || chatProcessId.isBlank()) {
            // Session exists but has no chat-process bootstrapped yet —
            // valid state between session-create and the first
            // SessionChatBootstrapper.ensureChatProcess call. Empty
            // history is the right answer, not 404.
            return List.of();
        }

        List<ChatMessageDocument> messages = chatMessageService.activeHistory(
                tenant, sessionId, chatProcessId);

        int cap = (limit != null && limit > 0) ? limit : DEFAULT_LIMIT;
        if (messages.size() > cap) {
            messages = messages.subList(messages.size() - cap, messages.size());
        }

        return messages.stream()
                .map(ChatHistoryController::toDto)
                .toList();
    }

    private static ChatMessageDto toDto(ChatMessageDocument doc) {
        return ChatMessageDto.builder()
                .messageId(doc.getId())
                .thinkProcessId(doc.getThinkProcessId())
                .role(doc.getRole())
                .content(doc.getContent())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    private static String currentUser(HttpServletRequest request) {
        Object u = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }
}
