package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Starts the worker pool — the VALIDATE point: at start the source must be
 * measured and a task must exist, otherwise the tool fails with a named
 * problem instead of a half-configured run. Optionally re-queues the failed
 * chunks (re-run semantics).
 */
@Component
@RequiredArgsConstructor
public class WowbaggerStartTool extends WowbaggerBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final WowbaggerPoolService pool;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "reRunFailed",
                            Map.of(
                                    "type",
                                    "boolean",
                                    "description",
                                    "Re-queue the failed chunks before starting (retryFailed). Ignored "
                                            + "when there are none."),
                            "force",
                            Map.of(
                                    "type",
                                    "boolean",
                                    "description",
                                    "Re-process the WHOLE source from record 0, overwriting "
                                            + "published chunk docs (task/model changed). Mutually "
                                            + "exclusive with reRunFailed.")),
            "required", java.util.List.of());

    @Override
    public String name() {
        return "wowbagger_start";
    }

    @Override
    public String description() {
        return "Start the worker pool with the current structure. Fails with a named "
                + "problem list when task/source are missing. Set threadsDesired > 0 "
                + "(wowbagger_set_threads) or workers won't rotate.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) throws ToolException {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        WowbaggerState s = pool.structure(process.getId());
        StringBuilder problems = new StringBuilder();
        if (s.getTask() == null || s.getTask().isBlank()) {
            problems.append("- no task — configure the per-record instruction first (wowbagger_configure)\n");
        }
        if (s.getSourcePath() == null || s.getSourcePath().isBlank()) {
            problems.append("- no source — configure the workspace file (import documents into the workspace first)\n");
        }
        if (problems.length() > 0) {
            throw new ToolException("cannot start — the structure is incomplete:\n" + problems);
        }
        boolean reRunFailed = booleanParam(params, "reRunFailed", false);
        boolean force = booleanParam(params, "force", false);
        if (reRunFailed && force) {
            throw new ToolException("reRunFailed and force are mutually exclusive — reRunFailed repairs the "
                    + "failure ledger, force re-processes the whole source from record 0");
        }
        WowbaggerPoolService.RunView view;
        try {
            if (reRunFailed) {
                view = pool.reRunFailed(process);
            } else {
                view = pool.start(process, force);
            }
        } catch (java.io.IOException e) {
            throw new ToolException("cannot start: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Model-gate refusals and recipe problems come through as clear
            // messages — the agent (and the user) must see them, not a stack trace.
            throw new ToolException(e.getMessage(), e);
        }
        if (view.threadsActive() == 0 && s.getThreadsDesired() > 0) {
            // The runner syncs threads within a tick; report the desire.
            return Map.of("started", true, "threadsDesired", s.getThreadsDesired(), "notes", "runner starting workers");
        }
        return Map.of("started", true, "threadsDesired", s.getThreadsDesired());
    }
}
