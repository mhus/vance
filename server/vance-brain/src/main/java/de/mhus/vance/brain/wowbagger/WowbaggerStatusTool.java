package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The precise- numbers fallback: the full current structure and the pool
 * state as JSON. The prompt already carries a rendered status block — this
 * tool exists for the rare answer that needs machine-readable values.
 */
@Component
@RequiredArgsConstructor
public class WowbaggerStatusTool extends WowbaggerBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final WowbaggerPoolService pool;

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of());

    @Override
    public String name() {
        return "wowbagger_status";
    }

    @Override
    public String description() {
        return "Return the exact Wowbagger structure and pool state as JSON "
                + "(task, source, pointer, records done/total, threads, failed chunks). "
                + "The prompt status block usually suffices — use this for precise numbers.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) throws ToolException {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        WowbaggerState structure = pool.structure(process.getId());
        boolean running = pool.isRunning(process.getId());
        WowbaggerPoolService.RunView view =
                new WowbaggerPoolService.RunView(structure, running, running ? structure.getThreadsDesired() : 0, null);
        Map<String, Object> out = new LinkedHashMap<>();
        WowbaggerState s = view.structure();
        out.put("running", view.running());
        out.put("threadsActive", view.threadsActive());
        out.put("threadsDesired", s.getThreadsDesired());
        out.put("task", s.getTask());
        out.put("source", s.getSourcePath());
        out.put("workTarget", s.getWorkTargetName());
        out.put("inputFormat", s.getInputFormat());
        out.put("outputFormat", s.getOutputFormat());
        out.put("outputDoc", s.getOutputDocPath());
        out.put("chunkSize", s.getChunkSize());
        out.put("pointer", s.getPointer());
        out.put("recordsDone", s.getRecordsDone());
        out.put("recordsTotal", s.getRecordsTotal());
        out.put("chunksTotal", s.getChunksTotal());
        out.put("retries", s.getCounters().getRetries());
        out.put("failedChunks", s.getFailedChunks());
        out.put("wakeEveryRecords", s.getWakeEveryRecords());
        out.put("wakeEverySeconds", s.getWakeEverySeconds());
        out.put("failureCooldownSeconds", s.getFailureCooldownSeconds());
        out.put("failureCount", s.getFailureCount());
        out.put("workerRecipe", s.getWorkerRecipe() == null ? "wowbagger-worker" : s.getWorkerRecipe());
        out.put("resolvedWorkerModel", s.getResolvedWorkerModel());
        out.put("modelApproved", pool.isWorkerModelApproved(process, s));
        out.put("finished", s.isFinished());
        if (view.runError() != null) {
            out.put("runError", view.runError());
        }
        return out;
    }
}
