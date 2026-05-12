package de.mhus.vance.brain.session;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Drives the {@link SessionMetadataSuggester} on the first complete
 * user/assistant exchange of a session. Listens to
 * {@link ChatMessageAppendedEvent}; on each {@code ASSISTANT} append
 * it checks whether (a) at least one Q&amp;A pair now exists, and
 * (b) the session still has empty title/icon/color and the user has
 * not touched the session metadata. Suggester runs asynchronously so
 * the chat hot path is unaffected.
 *
 * <p>See {@code specification/session-lifecycle.md} §14.1.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionMetadataSuggesterTrigger {

    private final SessionService sessionService;
    private final ChatMessageService chatMessageService;
    private final SessionMetadataSuggester suggester;

    @Async
    @EventListener
    public void onChatMessageAppended(ChatMessageAppendedEvent event) {
        ChatMessageDocument msg = event.message();
        if (msg.getRole() != ChatRole.ASSISTANT) return;

        SessionDocument session = sessionService.findBySessionId(msg.getSessionId()).orElse(null);
        if (session == null) return;
        if (session.getUserTouchedAt() != null) return;
        if (session.getTitle() != null
                && session.getIcon() != null
                && session.getColor() != null) {
            return;
        }
        // Suggester is most useful once. Trigger only when there is
        // exactly one user message + one assistant message in the
        // session — beyond that, the LLM-generated title would not
        // improve enough to justify the extra call.
        long userMsgs = chatMessageService.countBySessionAndRole(
                session.getTenantId(), session.getSessionId(), ChatRole.USER);
        long assistantMsgs = chatMessageService.countBySessionAndRole(
                session.getTenantId(), session.getSessionId(), ChatRole.ASSISTANT);
        if (userMsgs < 1 || assistantMsgs < 1) return;
        if (assistantMsgs > 1) return;

        log.debug("Triggering metadata suggester for session='{}'", session.getSessionId());
        suggester.suggest(session);
    }
}
