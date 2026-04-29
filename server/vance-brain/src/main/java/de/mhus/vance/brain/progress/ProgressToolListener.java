package de.mhus.vance.brain.progress;

import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.progress.UsageDelta;
import de.mhus.vance.brain.tools.ToolInvocationListener;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayDeque;
import java.util.Deque;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Bridges the tools layer to the user-progress side-channel: builds a
 * {@link ToolInvocationListener} that opens a correlated operation on
 * {@link StatusTag#TOOL_START} and closes it on {@link StatusTag#TOOL_END},
 * carrying the per-tool {@link UsageDelta} (token delta read from
 * {@link LlmCallTracker}, wall-clock from the listener's {@code elapsedMs}).
 *
 * <p>One listener instance per (process, lane-turn) — captured into the
 * {@code ContextToolsApi} that's handed to the engine for the call. The
 * listener keeps a per-instance frame stack so nested tool dispatches
 * (tool-A invokes tool-B) line up correctly; the surrounding tool layer
 * still calls {@code before}/{@code after} sequentially per call, so the
 * stack stays shallow in practice.
 */
@Component
@RequiredArgsConstructor
public class ProgressToolListener {

    private static final int DETAIL_MAX = 200;

    private final ProgressEmitter emitter;
    private final LlmCallTracker llmCallTracker;

    public ToolInvocationListener forProcess(ThinkProcessDocument process) {
        String processId = process.getId();
        Deque<OpFrame> stack = new ArrayDeque<>();
        return new ToolInvocationListener() {
            @Override
            public void before(String toolName) {
                String operationId = emitter.openOperation(
                        process, StatusTag.TOOL_START, "Calling tool: " + toolName);
                stack.push(new OpFrame(operationId, llmCallTracker.snapshot(processId)));
            }

            @Override
            public void after(String toolName, long elapsedMs, @Nullable Throwable error) {
                OpFrame frame = stack.pollFirst();
                if (frame == null) {
                    // Mismatched before/after — should never happen, but
                    // degrade gracefully to an uncorrelated end-ping.
                    emitter.emitStatus(process, StatusTag.TOOL_END,
                            "Tool " + toolName + " done (" + elapsedMs + "ms)");
                    return;
                }
                UsageDelta usage = buildUsage(processId, frame.startSnapshot, elapsedMs);
                if (error != null) {
                    emitter.emitStatus(process, StatusPayload.builder()
                            .tag(StatusTag.TOOL_END)
                            .text("Tool " + toolName + " failed (" + elapsedMs + "ms)")
                            .detail(abbrev(error.getMessage()))
                            .operationId(frame.operationId)
                            .usage(usage)
                            .build());
                    return;
                }
                emitter.closeOperation(
                        process,
                        frame.operationId,
                        StatusTag.TOOL_END,
                        "Tool " + toolName + " done (" + elapsedMs + "ms)",
                        usage);
            }
        };
    }

    private UsageDelta buildUsage(
            @Nullable String processId,
            LlmCallTracker.Snapshot startSnapshot,
            long elapsedMs) {
        LlmCallTracker.Snapshot delta = llmCallTracker.snapshot(processId).minus(startSnapshot);
        return UsageDelta.builder()
                .tokensIn(clampInt(delta.tokensIn()))
                .tokensOut(clampInt(delta.tokensOut()))
                .llmCalls(Math.max(0, delta.calls()))
                .elapsedMs(elapsedMs)
                .build();
    }

    private static int clampInt(long v) {
        if (v <= 0) return 0;
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }

    private static String abbrev(@Nullable String s) {
        if (s == null) return "(no message)";
        if (s.length() <= DETAIL_MAX) return s;
        return s.substring(0, DETAIL_MAX) + "…";
    }

    private record OpFrame(String operationId, LlmCallTracker.Snapshot startSnapshot) {}
}
