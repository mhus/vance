package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Read-only on-demand view of THIS process' run: phase, elapsed time,
 * last result/failure, and the script's console output — more of it than
 * the prompt status block's 5-line excerpt.
 *
 * <p>Why a tool at all: the status block is re-rendered per turn, which
 * answers "how is it going?" mid-run without a call — but it is a
 * passive excerpt. When the user explicitly wants the log ("show me the
 * output so far"), a timer wakeup lands the agent in a fresh turn whose
 * block still caps at five lines; this tool fetches up to the ring's
 * full 100-line window (live mid-run, kept readable after the terminal,
 * persisted-tail fallback after a pod restart — the same live-first
 * chain the status block uses).
 */
@Component
@RequiredArgsConstructor
public class HactarStatusTool extends HactarBaseTool {

    /** Ring cap (HactarConsoleLog.MAX_LINES) — the tool never asks for more. */
    static final int MAX_CONSOLE_LINES = 100;

    private static final int DEFAULT_CONSOLE_LINES = 20;

    private final ThinkProcessService thinkProcessService;
    private final HactarStateStore stateStore;
    private final HactarRunService runService;
    private final HactarConsoleLog consoleLog;

    @Override
    public String name() {
        return "hactar_status";
    }

    @Override
    public String description() {
        return "Read the current run status of this process on demand: phase, elapsed time, "
                + "last result or failure, and the script's console output. Params: "
                + "consoleLines (default 20, max 100). The output is LIVE while the run "
                + "executes (arrived lines so far), the kept ring after the terminal, and "
                + "the persisted tail after a brain restart. The status block in your "
                + "prompt already carries a 5-line excerpt — call this when the user wants "
                + "the fuller log or when a wakeup landed you mid-run and you owe a "
                + "detailed report.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "consoleLines",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "How many console lines to return (default 20, max 100 — the oldest "
                                + "beyond the cap drop off the in-memory ring)"));
        return Map.of("type", "object", "properties", props, "required", List.of());
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        String processId = process.getId();
        HactarState s = stateStore.load(process);
        boolean running = runService.isRunning(processId);

        int consoleLines = clampLines(intParam(params, "consoleLines"));
        String liveConsole = consoleLog.renderTail(processId, consoleLines);
        String console =
                liveConsole.isEmpty() ? ConsoleExcerpt.of(s.getConsoleTail(), consoleLines, 8000) : liveConsole;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", running);
        out.put("phase", s.getStatus() == null ? null : s.getStatus().name());
        out.put("scriptRef", s.getScriptRef());
        out.put("validateBeforeRun", s.isValidateBeforeRun());
        if (running) {
            Long startedAt = runService.runStartedAtMs(processId);
            out.put("elapsedMs", startedAt == null ? null : System.currentTimeMillis() - startedAt);
        }
        if (s.getExecutionResult() != null) out.put("executionResult", s.getExecutionResult());
        if (s.getExecutionError() != null) out.put("executionError", s.getExecutionError());
        if (s.getExecutionErrorClass() != null) out.put("executionErrorClass", s.getExecutionErrorClass());
        if (s.getFailureReason() != null) out.put("failureReason", s.getFailureReason());
        out.put("consoleLinesRequested", consoleLines);
        out.put("console", console);
        return out;
    }

    private static int clampLines(@org.jspecify.annotations.Nullable Integer raw) {
        if (raw == null) return DEFAULT_CONSOLE_LINES;
        return Math.max(1, Math.min(MAX_CONSOLE_LINES, raw));
    }

    private static @org.jspecify.annotations.Nullable Integer intParam(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
