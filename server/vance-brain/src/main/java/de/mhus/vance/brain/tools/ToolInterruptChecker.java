package de.mhus.vance.brain.tools;

import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Cheap, reusable mid-operation interrupt check for long-running server
 * tools (research_investigate, research_rich, compose runs, image
 * generation, …). The engine tool loop only checks the halt flag
 * <em>between</em> tool calls, so a tool that blocks for seconds across
 * several internal stages (LLM plan/evaluate, parallel searches, batch
 * tasks) would otherwise ignore an ESC / {@code /pause} until it returns.
 * A tool calls {@link #throwIfHalted} at its internal stage boundaries
 * with the {@code processId} from its {@code ToolInvocationContext} to bail
 * promptly; the thrown {@link ToolInterruptedException} is surfaced as a
 * tool error and the engine then bails at its next loop-head check.
 *
 * <p>Checks only the out-of-band halt flag — the fast signal that
 * {@code SessionLifecycleService.pauseActiveInSession} sets immediately.
 * The PAUSED status flip is queued behind the busy lane and cannot land
 * mid-tool anyway, so the flag is the only reliable in-flight signal.
 */
@Service
@RequiredArgsConstructor
public class ToolInterruptChecker {

    private final ThinkProcessService thinkProcessService;

    /** True when the process has an out-of-band halt (ESC / /pause) pending. */
    public boolean isHalted(@Nullable String processId) {
        return processId != null && !processId.isBlank()
                && thinkProcessService.isHaltRequested(processId);
    }

    /** Throws {@link ToolInterruptedException} when halted; no-op otherwise. */
    public void throwIfHalted(@Nullable String processId) {
        if (isHalted(processId)) {
            throw new ToolInterruptedException(processId);
        }
    }
}
