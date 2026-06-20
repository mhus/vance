package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
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

    public WorkTargetDispatcher(
            WorkTargetService workTargetService,
            ThinkProcessService thinkProcessService,
            @Lazy ToolDispatcher toolDispatcher) {
        this.workTargetService = workTargetService;
        this.thinkProcessService = thinkProcessService;
        this.toolDispatcher = toolDispatcher;
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
        } else {
            // WorkTargetKind.WORK
            if (!p.containsKey("dirName")
                    && target.dirName() != null && !target.dirName().isBlank()) {
                p.put("dirName", target.dirName());
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
}
