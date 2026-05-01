package de.mhus.vance.brain.progress;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link ThinkProcessStatusChangedEvent} and emits
 * engine-lifecycle status pings on the user-progress side-channel.
 * Lets clients render "[engine_paused] worker-x" etc. parallel to
 * the existing {@code [tool_start]} / {@code [tool_end]} lines.
 *
 * <p>The transition-to-status ping is intentionally separate from
 * the immediate ENGINE_HALT_REQUESTED ping (emitted by the WS
 * handlers when the request first arrives) — the user sees both
 * "I asked it to pause" and "it actually paused" with the gap
 * between them being the time the engine took to reach the next
 * safe boundary.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EngineLifecycleProgressListener {

    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        ThinkProcessStatus prior = event.priorStatus();
        ThinkProcessStatus next = event.newStatus();
        if (prior == next) return;

        // Translate the transition to a tag. Many transitions are
        // internal noise (INIT → IDLE, etc.) — we only surface the
        // ones the user actually wants to see.
        StatusTag tag = mapTransition(prior, next);
        if (tag == null) return;

        ThinkProcessDocument process = thinkProcessService.findById(event.processId())
                .orElse(null);
        if (process == null) return;

        String text = describe(tag, prior, next, process);
        progressEmitter.emitStatus(process, tag, text);
    }

    private static StatusTag mapTransition(ThinkProcessStatus prior, ThinkProcessStatus next) {
        // Turn boundaries: handled by the runTurn wrapper, not here —
        // status RUNNING ↔ IDLE happens inside engines and we don't
        // want double-emission. Pause/resume/close are the
        // user-visible lifecycle moments.
        return switch (next) {
            case PAUSED -> StatusTag.ENGINE_PAUSED;
            case CLOSED -> StatusTag.ENGINE_CLOSED;
            case IDLE -> (prior == ThinkProcessStatus.PAUSED
                    || prior == ThinkProcessStatus.SUSPENDED)
                    ? StatusTag.ENGINE_RESUMED : null;
            default -> null;
        };
    }

    private static String describe(
            StatusTag tag,
            ThinkProcessStatus prior,
            ThinkProcessStatus next,
            ThinkProcessDocument process) {
        String name = process.getName();
        return switch (tag) {
            case ENGINE_PAUSED -> name + " paused";
            case ENGINE_RESUMED -> name + " resumed";
            case ENGINE_CLOSED -> {
                CloseReason reason = process.getCloseReason();
                yield reason == null
                        ? name + " closed"
                        : name + " closed (" + reason.name().toLowerCase() + ")";
            }
            default -> name + " " + next.name().toLowerCase();
        };
    }
}
