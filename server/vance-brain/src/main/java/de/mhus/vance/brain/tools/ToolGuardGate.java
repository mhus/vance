package de.mhus.vance.brain.tools;

import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The Shooty TOOL-point hook for the {@link ToolDispatcher}: gates
 * exec-run tool calls before they execute. The scope is name-derived
 * (like {@link ToolFamily}): {@code exec_run} and its
 * {@code work_}/{@code client_} backends — every surface a model can
 * reach; other tool families may join later (shooty.md §2.4).
 *
 * <p>The glue is deliberately thin and fail-closed: the guard logic
 * (points, fail strategies, re-entrancy, metrics) lives in
 * {@link ShootyGuardService#gateTool}; this class only decides *whether*
 * a call is gate-worthy and loads the process. A call without a process
 * context (headless, system) has no recipe and is never gated.
 */
@Service
@Slf4j
public class ToolGuardGate {

    private final ObjectProvider<ShootyGuardService> guardProvider;
    private final ThinkProcessService thinkProcessService;

    public ToolGuardGate(ObjectProvider<ShootyGuardService> guardProvider, ThinkProcessService thinkProcessService) {
        // Lazy provider — breaks the cycle ToolDispatcher → ToolGuardGate
        // → ShootyGuardService → ToolDispatcher. Only exec-run calls
        // ever resolve the service.
        this.guardProvider = guardProvider;
        this.thinkProcessService = thinkProcessService;
    }

    /**
     * Whether the TOOL point covers {@code toolName}: the exec-run
     * surfaces ({@code exec_run}, {@code work_exec_run},
     * {@code client_exec_run}). Purely name-derived so no tool has to
     * declare anything.
     */
    public static boolean guardsTool(String toolName) {
        return "exec_run".equals(toolName) || toolName.endsWith("_exec_run");
    }

    /**
     * The denial reason for this tool call, or {@code null} when it may
     * proceed. Fail-closed on the glue path too: a guard subsystem that
     * cannot be consulted fails the exec call rather than opening the
     * gate silently.
     */
    public @Nullable String gate(String toolName, Map<String, Object> params, @Nullable ToolInvocationContext ctx) {
        if (ctx == null || StringUtils.isBlank(ctx.processId()) || !guardsTool(toolName)) {
            return null;
        }
        ShootyGuardService guards = guardProvider.getIfAvailable();
        if (guards == null) {
            log.warn("Tool guard requested for '{}' but Shooty is unavailable — fail-closed", toolName);
            return "Guard failed (fail-closed): guard subsystem unavailable";
        }
        ThinkProcessDocument process =
                thinkProcessService.findById(ctx.processId()).orElse(null);
        if (process == null) {
            // No process context — no recipe to resolve guards from.
            return null;
        }
        try {
            return guards.gateTool(process, toolName, params);
        } catch (RuntimeException e) {
            log.warn(
                    "Tool guard evaluation failed for '{}' (process='{}') — fail-closed: {}",
                    toolName,
                    ctx.processId(),
                    e.toString());
            return "Guard failed (fail-closed): " + e.getMessage();
        }
    }
}
