package de.mhus.vance.brain.chat;

import de.mhus.vance.api.chat.ChatMessageDto;
import de.mhus.vance.api.chat.SessionCropRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageDtoMapper;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pull endpoint for the persisted chat history of a session.
 *
 * <p>Returns the session's scrollback: the messages of <em>every</em>
 * process of the session, minus those already rolled into a memory
 * compaction ({@link ChatMessageService#activeHistoryWithInterimForSession}).
 * Worker output is part of it — it is pushed live to the same clients and
 * rendered as a {@code [processName · role]} note, so leaving it out here
 * would make a reload lose what the user just watched
 * ({@code planning/process-visibility.md} §5.3). Each row carries its
 * {@code processName} so the client can tell notes from the chat turn.
 *
 * <p>The crop view ({@code includeRemoved}) stays scoped to the
 * chat-process — a user may not crop a worker's transcript.
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
    private final ThinkProcessService thinkProcessService;
    private final RequestAuthority authority;

    @GetMapping("/{sessionId}/messages")
    public List<ChatMessageDto> listMessages(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "limit", required = false) @Nullable Integer limit,
            @RequestParam(value = "includeRemoved", required = false, defaultValue = "false")
                    boolean includeRemoved,
            HttpServletRequest request) {

        String currentUser = currentUser(request);

        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));

        // Multi-user routing (planning/multi-user-sessions.md §2.5):
        // shared sessions expose their chat history to any
        // authenticated user in the same tenant — the owner opted in
        // via allowMultipleClients. Private sessions stay owner-only.
        // The crop view (includeRemoved) is owner-only regardless.
        boolean isOwner = currentUser.equals(session.getUserId());
        if (includeRemoved && !isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Crop view is owner-only");
        }
        if (!isOwner && !session.isAllowMultipleClients()) {
            log.debug("Chat history access denied: session='{}' owner='{}' caller='{}'",
                    sessionId, session.getUserId(), currentUser);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Session '" + sessionId + "' belongs to another user");
        }
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()), Action.READ);

        String chatProcessId = session.getChatProcessId();
        if (includeRemoved && (chatProcessId == null || chatProcessId.isBlank())) {
            // Session exists but has no chat-process bootstrapped yet —
            // valid state between session-create and the first
            // SessionChatBootstrapper.ensureChatProcess call. Empty
            // history is the right answer, not 404.
            return List.of();
        }

        // Crop editor (includeRemoved): the conversation *of the chat-process*
        // incl. already-removed messages (so they can be restored), minus
        // interim noise. Scoped to the chat-process on purpose — the user
        // must not crop a worker's transcript.
        //
        // Normal scrollback: the whole session, every process. Worker output
        // is pushed live to this client and rendered as a
        // [processName · role] note, so returning only the chat-process here
        // would drop on reload what the user just watched happen
        // (planning/process-visibility.md §5.3). Interim stays (UI dims it),
        // removed is gone.
        List<ChatMessageDocument> messages = includeRemoved
                ? chatMessageService.historyForCrop(tenant, sessionId, chatProcessId)
                : chatMessageService.activeHistoryWithInterimForSession(tenant, sessionId);

        int cap = (limit != null && limit > 0) ? limit : DEFAULT_LIMIT;
        messages = includeRemoved
                ? tail(messages, cap)
                : applyScrollbackCap(messages, chatProcessId, cap);

        // One lookup for the whole page instead of one per message: the
        // scrollback now spans every process of the session, so a per-row
        // resolve would be N+1 on the hot reload path.
        Map<String, String> processNames = processNamesOf(tenant, sessionId);
        return messages.stream()
                .map(doc -> toDto(doc, processNames.get(doc.getThinkProcessId())))
                .toList();
    }

    /**
     * Cap the session-wide scrollback without ever evicting the user's own
     * conversation.
     *
     * <p>A plain newest-N cut over the merged stream would let one chatty
     * worker — Frankie emits an interim note per tool batch — push the whole
     * human conversation out of the reload window, which is precisely the
     * data loss the session-wide history was introduced to prevent. So the
     * chat-process rows get the budget first and worker notes fill whatever
     * is left. A long conversation therefore shows fewer notes rather than
     * losing itself.
     *
     * <p>Order is preserved by filtering the already-chronological input
     * instead of merging two lists back together.
     */
    static List<ChatMessageDocument> applyScrollbackCap(
            List<ChatMessageDocument> messages, @Nullable String chatProcessId, int cap) {
        if (messages.size() <= cap) {
            return messages;
        }
        List<ChatMessageDocument> own = new ArrayList<>();
        List<ChatMessageDocument> notes = new ArrayList<>();
        for (ChatMessageDocument m : messages) {
            if (chatProcessId != null && chatProcessId.equals(m.getThinkProcessId())) {
                own.add(m);
            } else {
                notes.add(m);
            }
        }
        Set<String> keep = new HashSet<>();
        for (ChatMessageDocument m : tail(own, cap)) {
            keep.add(m.getId());
        }
        int remaining = cap - Math.min(own.size(), cap);
        for (ChatMessageDocument m : tail(notes, remaining)) {
            keep.add(m.getId());
        }
        List<ChatMessageDocument> out = new ArrayList<>(Math.min(cap, messages.size()));
        for (ChatMessageDocument m : messages) {
            if (keep.contains(m.getId())) {
                out.add(m);
            }
        }
        return out;
    }

    /** The newest {@code n} entries of an ascending list. */
    private static List<ChatMessageDocument> tail(List<ChatMessageDocument> list, int n) {
        if (n <= 0) {
            return List.of();
        }
        return list.size() <= n ? list : list.subList(list.size() - n, list.size());
    }

    /** {@code thinkProcessId → name} for every process of the session. */
    private Map<String, String> processNamesOf(String tenant, String sessionId) {
        Map<String, String> names = new HashMap<>();
        for (ThinkProcessDocument p : thinkProcessService.findBySession(tenant, sessionId)) {
            if (p.getId() != null && p.getName() != null) {
                names.put(p.getId(), p.getName());
            }
        }
        return names;
    }

    /**
     * Modify/Crop the session's chat memory: remove and/or restore
     * messages. Owner-only. Returns the fresh crop list (all non-archived,
     * non-interim messages incl. removed) so the modal can re-render in one
     * round-trip. See {@code specification/public/session-crop.md}.
     */
    @PatchMapping("/{sessionId}/messages/crop")
    public List<ChatMessageDto> crop(
            @PathVariable("tenant") String tenant,
            @PathVariable("sessionId") String sessionId,
            @RequestBody SessionCropRequest body,
            HttpServletRequest request) {

        String currentUser = currentUser(request);
        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session '" + sessionId + "' not found"));
        if (!currentUser.equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Session '" + sessionId + "' belongs to another user");
        }
        authority.enforce(request,
                new Resource.Session(tenant, session.getProjectId(), session.getSessionId()),
                Action.WRITE);

        String chatProcessId = session.getChatProcessId();
        if (chatProcessId == null || chatProcessId.isBlank()) {
            return List.of();
        }

        if (body.getRemove() != null && !body.getRemove().isEmpty()) {
            chatMessageService.markRemoved(tenant, sessionId, body.getRemove());
        }
        if (body.getRestore() != null && !body.getRestore().isEmpty()) {
            chatMessageService.unmarkRemoved(tenant, sessionId, body.getRestore());
        }

        // Crop is chat-process-scoped, so every row carries the same name.
        String chatProcessName = thinkProcessService.findById(chatProcessId)
                .map(ThinkProcessDocument::getName)
                .orElse(null);
        return chatMessageService.historyForCrop(tenant, sessionId, chatProcessId).stream()
                .map(doc -> toDto(doc, chatProcessName))
                .toList();
    }

    private static ChatMessageDto toDto(
            ChatMessageDocument doc, @Nullable String processName) {
        return ChatMessageDtoMapper.toDto(doc, processName);
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
