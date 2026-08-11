package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.brain.daemon.DaemonRegistry;
import de.mhus.vance.brain.daemon.DaemonToolInvoker;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Wrapper params that legitimately have no counterpart on every backend.
     * {@code dirName} names a workspace RootDir — a WORK concept — and is
     * stripped before a CLIENT/DAEMON call; every wrapper documents it as
     * ignored there. The one declared exception to "declared means it works".
     */
    private static final Set<String> WORK_ONLY_PARAMS = Set.of("dirName");

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
                                        Tool wrapper,
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
            rejectUnknownParams(ctx, wrapper, clientName, p);
            // Foot tools don't take dirName — strip if the LLM passed one through.
            p.remove("dirName");
            backendName = clientName;
        } else if (target.kind() == WorkTargetKind.DAEMON) {
            // Route the client_* backend tool over the named daemon's WS
            // instead of the session-bound Foot. The daemon announced its
            // tools under the same client_* names, so clientName is the
            // wire name. Foot client tools don't take dirName.
            rejectUnknownParams(ctx, wrapper, clientName, p);
            p.remove("dirName");
            DaemonRegistry.DaemonKey key = daemonKey(process, target.targetName());
            return daemonToolInvoker.invoke(
                    key, clientName, p, Duration.ofSeconds(daemonTimeoutSeconds));
        } else {
            // WorkTargetKind.WORK
            rejectUnknownParams(ctx, wrapper, workName, p);
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
        // invokeDelegate, not invoke: the backends are deferred, and a plain
        // invoke would activate the one we happened to pick — the next turn
        // would then show file_read AND work_file_read, which is the exact
        // ambiguity this wrapper removes.
        return bus.invokeDelegate(backendName, p);
    }

    /**
     * Rejects params that neither the wrapper nor the backend it routes to
     * declares — instead of passing them along to be silently dropped.
     *
     * <p>Why this is worth an exception: the wrapper and its two backends
     * are three separate schemas, and they drift. A param that exists in
     * one but not the other used to vanish without a trace, which leaves
     * the caller with a result that looks like the tool ignored it — and
     * a model that cannot tell "ignored" from "did nothing" re-tries with
     * a bigger number, then a different number, then starts diagnosing the
     * tool. One clear error ends that in a single turn. (2026-08-11: a
     * Frankie turn spent 8 iterations and four spawned diagnostic workers
     * on a {@code file_read maxChars} that the CLIENT backend never read.)
     *
     * <p>Two distinct failures, both silent before:
     *
     * <ul>
     *   <li><b>Unknown to everyone</b> — neither the wrapper nor the backend
     *       declares it. A hallucinated param name.</li>
     *   <li><b>Declared but inert</b> — the wrapper advertises it, the active
     *       backend does not implement it. This is the more dangerous half,
     *       because the caller read the name off the schema and has every
     *       reason to trust it. {@code file_read maxChars} on CLIENT was
     *       exactly this shape.</li>
     * </ul>
     *
     * <p>Params only the <em>backend</em> declares stay allowed: backends
     * legitimately expose extras the wrapper doesn't advertise, and those
     * calls have always worked. {@code dirName} is the one declared
     * exception — WORK-only by design, stripped for CLIENT/DAEMON, and
     * documented as ignored there by every wrapper.
     *
     * <p>Fail-open: when the backend can't be resolved or declares no
     * properties, nothing is rejected. A validation layer must not be the
     * reason a working call starts failing.
     *
     * <p>The steady state is that neither case can occur —
     * {@code WorkTargetToolSymmetryTest} in {@code qa/} keeps the three
     * schemas aligned. This is the runtime net for the drift that slips
     * past it, e.g. a client-supplied tool pack overriding a backend name.
     */
    private void rejectUnknownParams(ToolInvocationContext ctx,
                                     Tool wrapper,
                                     String backendName,
                                     Map<String, Object> params) {
        if (params.isEmpty()) return;
        Set<String> backendParams = declaredParams(resolveBackend(backendName, ctx));
        if (backendParams.isEmpty()) return;
        Set<String> wrapperParams = declaredParams(wrapper);

        List<String> unknown = params.keySet().stream()
                .filter(k -> !wrapperParams.contains(k) && !backendParams.contains(k))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            Set<String> accepted = new LinkedHashSet<>(wrapperParams);
            accepted.addAll(backendParams);
            throw new ToolException(wrapper.name() + " does not accept parameter(s) "
                    + unknown + " — accepted: " + accepted.stream().sorted().toList()
                    + ". The call was rejected instead of ignoring them silently; "
                    + "re-call with a supported parameter.");
        }

        List<String> inert = params.keySet().stream()
                .filter(k -> !backendParams.contains(k) && !WORK_ONLY_PARAMS.contains(k))
                .sorted()
                .toList();
        if (!inert.isEmpty()) {
            throw new ToolException(wrapper.name() + ": parameter(s) " + inert
                    + " are not supported by the active backend '" + backendName
                    + "' and would have had no effect. Supported there: "
                    + backendParams.stream().sorted().toList()
                    + ". This is a schema mismatch, not your mistake — report it; "
                    + "meanwhile use one of the supported parameters.");
        }
    }

    /** The wrapper's or backend's declared property names; empty when unknown. */
    private static Set<String> declaredParams(@Nullable Tool tool) {
        if (tool == null) return Set.of();
        Map<String, Object> schema = tool.paramsSchema();
        Object props = schema == null ? null : schema.get("properties");
        if (props instanceof Map<?, ?> m) {
            Set<String> out = new LinkedHashSet<>();
            for (Object k : m.keySet()) {
                if (k instanceof String s) out.add(s);
            }
            return out;
        }
        return Set.of();
    }

    private @Nullable Tool resolveBackend(String backendName, ToolInvocationContext ctx) {
        try {
            return toolDispatcher.resolve(backendName, ctx)
                    .map(ToolDispatcher.Resolved::tool)
                    .orElse(null);
        } catch (RuntimeException e) {
            // Resolution is a convenience for the error message — never let
            // it turn into the failure of the actual call.
            return null;
        }
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
