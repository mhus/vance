package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.brain.daemon.DaemonRegistry;
import de.mhus.vance.brain.daemon.DaemonToolInvoker;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Resolves the current {@link WorkTarget} of a process and computes
 * the backend tool name to dispatch to plus any param injection
 * needed.
 *
 * <p>Used by the generic {@code file_*} and {@code exec_*} wrappers
 * in this package. Keeps the dispatch logic in one place so all 12
 * wrappers stay thin and consistent.
 */
@Service
public class WorkTargetDispatcher {

    private final WorkTargetService workTargetService;
    private final ThinkProcessService thinkProcessService;
    private final ToolDispatcher toolDispatcher;
    private final DaemonToolInvoker daemonToolInvoker;

    /**
     * Per-invocation upper bound for DAEMON dispatch. Long-running exec
     * uses the poll-based pattern ({@code exec_run} returns fast, then
     * {@code exec_status}/{@code exec_tail}), so this only needs to
     * cover a single short round-trip.
     */
    @Value("${vance.worktarget.daemon-timeout-seconds:60}")
    long daemonTimeoutSeconds = 60;

    public WorkTargetDispatcher(
            WorkTargetService workTargetService,
            ThinkProcessService thinkProcessService,
            @Lazy ToolDispatcher toolDispatcher,
            DaemonToolInvoker daemonToolInvoker) {
        this.workTargetService = workTargetService;
        this.thinkProcessService = thinkProcessService;
        this.toolDispatcher = toolDispatcher;
        this.daemonToolInvoker = daemonToolInvoker;
    }

    /**
     * Dispatches a generic call to the right backend based on the
     * current {@link WorkTarget}. {@code clientName} and
     * {@code workName} are the two backend tool names this wrapper
     * can route to (e.g. {@code "client_file_read"} and
     * {@code "work_file_read"}). For WORK targets the
     * {@code dirName} from the active target is injected into
     * {@code params} when the caller didn't supply one.
     *
     * <p>Throws {@link ToolException} if the process can't be found,
     * for CLIENT targets when no Foot client is currently bound to
     * the session, or if the backend tool isn't in this engine's
     * allow-set.
     */
    public Map<String, Object> dispatch(ToolInvocationContext ctx,
                                        ToolBus bus,
                                        String clientName,
                                        String workName,
                                        @Nullable Map<String, Object> params) {
        ThinkProcessDocument process = thinkProcessService.findById(ctx.processId())
                .orElseThrow(() -> new ToolException(
                        "Process '" + ctx.processId() + "' not found"));
        WorkTarget target = workTargetService.current(process);
        Map<String, Object> p = params == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        String backendName;
        if (target.kind() == WorkTargetKind.CLIENT) {
            if (!workTargetService.clientConnected(process.getSessionId())) {
                throw new ToolException(
                        "WorkTarget is CLIENT but no Foot client is bound to this "
                                + "session — call work_target_set(kind=\"WORK\") or "
                                + "reconnect the foot CLI.");
            }
            // Foot tools don't take dirName — strip if the LLM passed one through.
            p.remove("dirName");
            backendName = clientName;
        } else if (target.kind() == WorkTargetKind.DAEMON) {
            // Route the client_* backend tool over the named daemon's WS
            // instead of the session-bound Foot. The daemon announced its
            // tools under the same client_* names, so clientName is the
            // wire name. Foot client tools don't take dirName.
            p.remove("dirName");
            DaemonRegistry.DaemonKey key = daemonKey(process, target.targetName());
            return daemonToolInvoker.invoke(
                    key, clientName, p, Duration.ofSeconds(daemonTimeoutSeconds));
        } else {
            // WorkTargetKind.WORK
            if (!p.containsKey("dirName")
                    && target.targetName() != null && !target.targetName().isBlank()) {
                p.put("dirName", target.targetName());
            }
            backendName = workName;
        }
        if (bus == null) {
            // 2-arg invoke path (typical: Agrajag-probe, internal
            // calls that don't carry an engine surface). Go straight
            // through the ToolDispatcher; the backend tool is gated
            // by its own permission checks, no allow-set filter
            // applies here.
            return toolDispatcher.invoke(backendName, p, ctx);
        }
        return bus.invoke(backendName, p);
    }

    /**
     * Builds the {@link DaemonRegistry.DaemonKey} for a DAEMON target.
     * The daemon is project-scoped, so the process's tenant + project
     * plus the target name fully address it. Surfaces a clean
     * {@link ToolException} (not a raw {@link IllegalArgumentException})
     * when the process is missing scope fields.
     */
    private DaemonRegistry.DaemonKey daemonKey(
            ThinkProcessDocument process, @Nullable String daemonName) {
        if (StringUtils.isBlank(process.getTenantId())
                || StringUtils.isBlank(process.getProjectId())) {
            throw new ToolException(
                    "WorkTarget is DAEMON but the process is missing tenant/project "
                            + "scope — cannot resolve daemon '" + daemonName + "'");
        }
        try {
            return new DaemonRegistry.DaemonKey(
                    process.getTenantId(), process.getProjectId(), daemonName);
        } catch (IllegalArgumentException ex) {
            throw new ToolException("invalid DAEMON work target: " + ex.getMessage(), ex);
        }
    }
}
