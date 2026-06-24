package de.mhus.vance.brain.prak;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Single integration point for periodic Prak: listens on
 * {@link ChatMessageAppendedEvent} and hands every ASSISTANT message
 * to {@link PrakPeriodicTrigger#maybeFire}. The trigger itself gates
 * by count / token-budget, so this listener is engine-agnostic — no
 * need to wire each engine (Arthur, Eddie, Ford, Marvin, …) with a
 * custom hook.
 *
 * <p>Why ASSISTANT: an assistant message marks the natural end of a
 * turn. USER and SYSTEM messages don't drive engine progress on their
 * own; firing on every USER-input would invoke the trigger
 * <em>before</em> the LLM has produced its reply, which is wasted
 * because the trigger's content (the LLM's analyses) is what we want
 * to rate.
 *
 * <p>Failures are logged + swallowed — the engine's main loop has
 * already succeeded by the time we get here.
 *
 * <p><b>Async on purpose.</b> {@link ChatMessageService#append} fires
 * {@link ChatMessageAppendedEvent} synchronously on the engine's lane
 * thread (Spring's default {@code SimpleApplicationEventMulticaster}),
 * so a slow Prak run would block the engine's turn from terminating —
 * a live regression shipped {@code default:fast} stuck in a
 * {@code HttpTimeoutException} retry loop for 87 seconds, and Lunkwill
 * couldn't transition {@code RUNNING -> IDLE} until Prak finished. The
 * trigger itself does heavy lifting (LLM call, Mongo writes, memory
 * promotions) that has no business living on the lane critical path.
 * {@code @Async} hands the work off to the default Spring task
 * executor — failures still log + swallow, and the engine's chat
 * message persistence has already happened before this listener runs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PrakPeriodicListener {

    private final PrakPeriodicTrigger trigger;
    private final ThinkProcessService thinkProcessService;
    private final SessionService sessionService;

    @Async
    @EventListener
    public void onChatMessageAppended(ChatMessageAppendedEvent event) {
        if (event == null) return;
        ChatMessageDocument m = event.message();
        if (m == null) return;
        if (m.getRole() != ChatRole.ASSISTANT) return;
        if (m.getThinkProcessId() == null || m.getThinkProcessId().isBlank()) return;

        try {
            Optional<ThinkProcessDocument> proc =
                    thinkProcessService.findById(m.getThinkProcessId());
            if (proc.isEmpty()) return;
            ThinkProcessDocument process = proc.get();
            String projectId = process.getSessionId() == null
                    ? ""
                    : sessionService.findBySessionId(process.getSessionId())
                            .map(SessionDocument::getProjectId).orElse("");
            trigger.maybeFire(process, projectId);
        } catch (RuntimeException e) {
            log.warn("Periodic-Prak listener failed for processId='{}': {}",
                    m.getThinkProcessId(), e.toString());
        }
    }
}
