package de.mhus.vance.brain.damogran;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link DamogranComposeResult} into a plain JSON-ish map for the
 * {@code compose_run} tool and the REST controller (shared shape).
 *
 * <p>Output artifacts are emitted with a {@code vance-workspace:} URI so a
 * client (Cortex/Workbook output region) can load the file from the workspace
 * REST surface without knowing the dir layout.
 */
final class DamogranResponse {

    private DamogranResponse() {}

    static Map<String, Object> toMap(DamogranComposeResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result.isSuccess());
        out.put("workspace", result.workspaceName());
        if (result.error() != null) {
            out.put("error", result.error());
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (DamogranTaskResult tr : result.taskResults()) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("status", tr.status().name().toLowerCase());
            if (!tr.outputs().isEmpty()) {
                task.put("outputs", outputs(result.workspaceName(), tr.outputs()));
            }
            if (tr.error() != null) {
                task.put("error", tr.error());
            }
            if (tr.log() != null && !tr.log().isBlank()) {
                task.put("log", tr.log());
            }
            tasks.add(task);
        }
        out.put("tasks", tasks);
        return out;
    }

    private static List<Map<String, Object>> outputs(String workspaceName, List<OutputArtifact> artifacts) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (OutputArtifact a : artifacts) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("path", a.path());
            o.put("uri", "vance-workspace:/" + workspaceName + "/" + a.path());
            if (a.kind() != null) {
                o.put("kind", a.kind());
            }
            if (a.mime() != null) {
                o.put("mime", a.mime());
            }
            if (a.title() != null) {
                o.put("title", a.title());
            }
            list.add(o);
        }
        return list;
    }
}
