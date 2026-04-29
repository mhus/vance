package de.mhus.vance.brain.progress;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.tools.ToolInvocationListener;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Bridges the tools layer to the user-progress side-channel: builds a
 * {@link ToolInvocationListener} that emits {@link StatusTag#TOOL_START}
 * and {@link StatusTag#TOOL_END} pings for a given think-process.
 *
 * <p>One listener instance per (process, lane-turn) — captured into the
 * {@code ContextToolsApi} that's handed to the engine for the call.
 */
@Component
@RequiredArgsConstructor
public class ProgressToolListener {

    private static final int DETAIL_MAX = 200;

    private final ProgressEmitter emitter;

    public ToolInvocationListener forProcess(ThinkProcessDocument process) {
        return new ToolInvocationListener() {
            @Override
            public void before(String toolName) {
                emitter.emitStatus(process, StatusTag.TOOL_START,
                        "Calling tool: " + toolName);
            }

            @Override
            public void after(String toolName, long elapsedMs, @Nullable Throwable error) {
                if (error != null) {
                    emitter.emitStatus(process,
                            de.mhus.vance.api.progress.StatusPayload.builder()
                                    .tag(StatusTag.TOOL_END)
                                    .text("Tool " + toolName + " failed (" + elapsedMs + "ms)")
                                    .detail(abbrev(error.getMessage()))
                                    .build());
                    return;
                }
                emitter.emitStatus(process, StatusTag.TOOL_END,
                        "Tool " + toolName + " done (" + elapsedMs + "ms)");
            }
        };
    }

    private static String abbrev(@Nullable String s) {
        if (s == null) return "(no message)";
        if (s.length() <= DETAIL_MAX) return s;
        return s.substring(0, DETAIL_MAX) + "…";
    }
}
