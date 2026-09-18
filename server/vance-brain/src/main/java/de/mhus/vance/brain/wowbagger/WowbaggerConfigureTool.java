package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Sets the structure fields (§4a.3) — the agent's write side: task, source,
 * input/output format, chunk size, wakeup interval, retry budget, output
 * doc. Omitted fields keep their current value. Only valid when the pool is
 * not running (structure changes mid-run would race the workers).
 */
@Component
@RequiredArgsConstructor
public class WowbaggerConfigureTool extends WowbaggerBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final WowbaggerPoolService pool;

    private static final Map<String, Object> SCHEMA = buildSchema();

    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.putAll(Map.ofEntries(
                Map.entry(
                        "task",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "The per-record task instruction handed to every worker "
                                        + "call. Be specific about the EXACT output format of one result line.")),
                Map.entry(
                        "source",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Source file path relative to the WORK-target workspace "
                                        + "root. Import documents into the workspace first (doc_* export or "
                                        + "file tools) — records are read as text lines.")),
                Map.entry(
                        "inputFormat",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                java.util.List.of("lines", "jsonl"),
                                "description",
                                "How one record is read from the source.")),
                Map.entry(
                        "outputFormat",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                java.util.List.of("lines", "jsonl"),
                                "description",
                                "How the worker reply is validated and stored. jsonl = one JSON "
                                        + "object per record (validated!). Default = inputFormat.")),
                Map.entry(
                        "outputDoc",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Document path for the merged final result. Default " + "_wowbagger/<run>/result.")),
                Map.entry(
                        "chunkSize",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Records per worker call (default 50; 100–200 works for short " + "records).")),
                Map.entry(
                        "wakeEveryRecords",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Commit interval for pool wakeups (default 1000; 0 = only "
                                        + "start/finish/failure).")),
                Map.entry(
                        "chunkRetries",
                        Map.of("type", "integer", "description", "Per-chunk retry budget (default 3).")),
                Map.entry(
                        "wakeEverySeconds",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Heartbeat wakeup while running: check in on the run when nothing "
                                        + "else produced news for this long (0 = off; e.g. 1800 = every 30 "
                                        + "minutes).")),
                Map.entry(
                        "failureCooldownSeconds",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Minimum seconds between failure wakeups (default 300; 0 = wake on "
                                        + "every failure). The failure counter still counts every failed "
                                        + "chunk.")),
                Map.entry(
                        "resetFailureCount",
                        Map.of(
                                "type",
                                "boolean",
                                "description",
                                "Acknowledge the accumulated failures: reset the failure counter "
                                        + "to zero (a re-run does this implicitly).")),
                Map.entry(
                        "sourceBackupMb",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Opt-in source durability for pod switches: when > 0, start() "
                                        + "copies the source file into a document if it is <= N MB — "
                                        + "a pod switch then loses nothing and resume restores it. "
                                        + "0 = off (default, fine for single-pod). Propose it to "
                                        + "the user once when the run is long-lived; the user decides.")),
                Map.entry(
                        "maxTokens",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Output-token cap per worker call (null/0 = the worker "
                                        + "recipe default). Scale with chunkSize and output "
                                        + "size, but keep it far below the model's context "
                                        + "window — gateways clamp max_tokens to it.")),
                Map.entry(
                        "workerRecipe",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Internal (internal: true) LightLm profile for worker calls — e.g. "
                                        + "a project-local recipe pinning the worker model tier. Default: "
                                        + "wowbagger-worker."))));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of());
        return schema;
    }

    @Override
    public String name() {
        return "wowbagger_configure";
    }

    @Override
    public String description() {
        return "Set Wowbagger's run structure (task, source, formats, chunk size, "
                + "wakeup intervals, retries, failure cooldown). Only while the pool is "
                + "stopped. Analyze the source first (read the first lines) before "
                + "setting inputFormat/task. resetFailureCount acknowledges failures. "
                + "The response carries the run's RootDir (workTarget) — import the "
                + "source into that root so the run survives restarts.";
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
        // Refuse — do NOT silently park. The pool.stop() here used to make
        // the "is it running" check below it unreachable (stop() flips
        // running synchronously), so every configure quietly killed a
        // running rotation, right down to a bare resetFailureCount.
        if (pool.isRunning(process.getId())) {
            throw new ToolException("the pool is running — park it first (wowbagger_stop or "
                    + "wowbagger_set_threads 0) and re-configure; structure changes mid-run would race the workers");
        }
        WowbaggerState s = pool.structure(process.getId());
        Map<String, Object> applied = new LinkedHashMap<>();
        try {
            String task = stringParam(params, "task", false);
            if (task != null) {
                s.setTask(task);
                applied.put("task", task);
            }
            String source = stringParam(params, "source", false);
            if (source != null) {
                s.setSourcePath(source);
                s.setRecordsTotal(-1L); // re-measure on start
                s.setChunksTotal(-1);
                applied.put("source", source);
            }
            String inputFormat = stringParam(params, "inputFormat", false);
            if (inputFormat != null) {
                s.setInputFormat(inputFormat.toLowerCase(java.util.Locale.ROOT));
                applied.put("inputFormat", s.getInputFormat());
            }
            String outputFormat = stringParam(params, "outputFormat", false);
            if (outputFormat != null) {
                s.setOutputFormat(outputFormat.toLowerCase(java.util.Locale.ROOT));
                applied.put("outputFormat", s.getOutputFormat());
            }
            String outputDoc = stringParam(params, "outputDoc", false);
            if (outputDoc != null) {
                s.setOutputDocPath(outputDoc);
                applied.put("outputDoc", outputDoc);
            }
            if (params != null && params.containsKey("chunkSize")) {
                int v = intParam(params, "chunkSize", s.getChunkSize());
                if (v < 1) {
                    throw new ToolException("chunkSize must be >= 1");
                }
                s.setChunkSize(v);
                s.setChunksTotal(s.getRecordsTotal() < 0 ? -1 : (int) ((s.getRecordsTotal() + v - 1) / v));
                applied.put("chunkSize", v);
            }
            if (params != null && params.containsKey("wakeEveryRecords")) {
                int v = intParam(params, "wakeEveryRecords", s.getWakeEveryRecords());
                s.setWakeEveryRecords(Math.max(0, v));
                applied.put("wakeEveryRecords", s.getWakeEveryRecords());
            }
            if (params != null && params.containsKey("chunkRetries")) {
                int v = intParam(params, "chunkRetries", s.getChunkRetries());
                if (v < 0) {
                    throw new ToolException("chunkRetries must be >= 0");
                }
                s.setChunkRetries(v);
                applied.put("chunkRetries", v);
            }
            if (params != null && params.containsKey("wakeEverySeconds")) {
                int v = intParam(params, "wakeEverySeconds", s.getWakeEverySeconds());
                if (v < 0) {
                    throw new ToolException("wakeEverySeconds must be >= 0");
                }
                s.setWakeEverySeconds(v);
                applied.put("wakeEverySeconds", v);
            }
            if (params != null && params.containsKey("failureCooldownSeconds")) {
                int v = intParam(params, "failureCooldownSeconds", s.getFailureCooldownSeconds());
                if (v < 0) {
                    throw new ToolException("failureCooldownSeconds must be >= 0");
                }
                s.setFailureCooldownSeconds(v);
                applied.put("failureCooldownSeconds", v);
            }
            if (params != null
                    && params.containsKey("resetFailureCount")
                    && booleanParam(params, "resetFailureCount", false)) {
                s.setFailureCount(0);
                applied.put("failureCount", 0L);
            }
            if (params != null && params.containsKey("sourceBackupMb")) {
                int v = intParam(params, "sourceBackupMb", s.getSourceBackupMb());
                if (v < 0) {
                    throw new ToolException("sourceBackupMb must be >= 0");
                }
                s.setSourceBackupMb(v);
                applied.put("sourceBackupMb", v);
            }
            if (params != null && params.containsKey("maxTokens")) {
                int v = intParam(params, "maxTokens", 0);
                s.setMaxTokens(v > 0 ? v : null);
                applied.put("maxTokens", s.getMaxTokens() == null ? "(recipe default)" : s.getMaxTokens());
            }
            if (params != null && params.containsKey("workerRecipe")) {
                String v = stringParam(params, "workerRecipe", false);
                s.setWorkerRecipe(v == null || v.isBlank() ? null : v);
                applied.put("workerRecipe", s.getWorkerRecipe() == null ? "(default)" : s.getWorkerRecipe());
            }
            // Cost gate: refuse a structure whose worker model is not approved
            // (setting wowbagger.allowed-models) — before the agent can start it.
            try {
                pool.checkModelApproval(process, s);
            } catch (RuntimeException e) {
                throw new ToolException(e.getMessage(), e);
            }
        } catch (ToolException e) {
            // Persist what was applied before the failure so the structure
            // never silently reverts.
            persistReflection(process, s);
            throw e;
        }
        String workRoot = pool.ensureWorkRoot(process, s);
        persistReflection(process, s);
        List<String> warnings = pool.sourceRootWarnings(process, s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", applied);
        out.put("workTarget", workRoot);
        out.put("warnings", warnings);
        return out;
    }

    private void persistReflection(ThinkProcessDocument process, WowbaggerState s) {
        // Route through the pool's persistence (engineParams) so the tool and
        // the pool share one serialization path.
        pool.structure(process.getId()); // touch to ensure handle exists
        pool.persistStructure(process, s);
    }
}
