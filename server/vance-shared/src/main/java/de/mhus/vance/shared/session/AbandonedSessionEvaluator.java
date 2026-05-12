package de.mhus.vance.shared.session;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Predicate service: is this session a candidate for hard-close on
 * suspend-sweep, instead of going through the archive? Three
 * conditions must all hold (see
 * {@code specification/session-lifecycle.md} §9.1):
 *
 * <ol>
 *   <li>No complete Q&amp;A pair — message counts show fewer than
 *       {@code min-qa-pairs} user-and-assistant pair(s).</li>
 *   <li>No side-effects — no tool-call markers in the chat history,
 *       no spawned sub-processes beyond the chat process itself.</li>
 *   <li>No user investment — {@code userTouchedAt} is unset on the
 *       session document (manual title edits, tags, pin, icon, color
 *       all set it).</li>
 * </ol>
 *
 * <p>Trip any condition and the session is <em>not</em> abandoned and
 * follows the regular archive path.
 *
 * <p>Tenant-level toggle:
 * {@code vance.session.abandoned-detection.enabled} (default {@code true})
 * disables the predicate wholesale. {@code min-qa-pairs} tunes the
 * threshold (default 1).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbandonedSessionEvaluator {

    /**
     * Tag prefixes that count as "side-effect with persistent reach"
     * on a chat message. Stable list — anything else is a chat-only
     * marker. See {@code planning/process-history-search.md} §5 for
     * the wider tag taxonomy.
     */
    private static final List<String> SIDE_EFFECT_TAG_PREFIXES = List.of(
            "TOOL_CALL:",
            "FILE_EDIT",
            "RESOURCE:");

    private final ChatMessageService chatMessageService;
    private final ThinkProcessService thinkProcessService;

    @Value("${vance.session.abandoned-detection.enabled:true}")
    private boolean enabled;

    @Value("${vance.session.abandoned-detection.min-qa-pairs:1}")
    private int minQaPairs;

    public boolean isAbandoned(SessionDocument session) {
        if (!enabled || session == null) return false;

        // 3. User investment — cheapest check, run first.
        if (session.getUserTouchedAt() != null) {
            return false;
        }

        String tenantId = session.getTenantId();
        String sessionId = session.getSessionId();

        // 1. No complete Q&A pair.
        long userMsgs = chatMessageService.countBySessionAndRole(
                tenantId, sessionId, ChatRole.USER);
        long assistantMsgs = chatMessageService.countBySessionAndRole(
                tenantId, sessionId, ChatRole.ASSISTANT);
        long pairs = Math.min(userMsgs, assistantMsgs);
        if (pairs >= minQaPairs) {
            return false;
        }

        // 2. No side-effects — tool-call tags first (cheap aggregate query).
        long sideEffectMsgs = chatMessageService.countBySessionAndAnyTagPrefix(
                tenantId, sessionId, SIDE_EFFECT_TAG_PREFIXES);
        if (sideEffectMsgs > 0) {
            return false;
        }

        // Sub-processes spawned beyond the chat process itself.
        String chatProcessId = session.getChatProcessId();
        List<ThinkProcessDocument> processes =
                thinkProcessService.findBySession(tenantId, sessionId);
        for (ThinkProcessDocument p : processes) {
            if (chatProcessId != null && chatProcessId.equals(p.getId())) continue;
            // A non-chat process exists — counts as side-effect activity.
            return false;
        }

        log.debug("Session '{}' classified as abandoned (userMsgs={}, assistantMsgs={})",
                sessionId, userMsgs, assistantMsgs);
        return true;
    }
}
