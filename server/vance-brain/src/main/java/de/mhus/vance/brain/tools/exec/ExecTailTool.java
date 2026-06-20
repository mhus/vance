package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.brain.execution.ExecutionRouter;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Last N lines of a job's stdout or stderr log file. Cheap targeted
 * read for "what is this thing currently spitting out?" — avoids
 * round-tripping the full inline output through {@code work_exec_status}.
 */
@Component
@RequiredArgsConstructor
public class ExecTailTool implements Tool {

    private static final int DEFAULT_LINES = 10;
    private static final int MAX_LINES = 500;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by work_exec_run."),
                    "n", Map.of(
                            "type", "integer",
                            "description", "Number of lines to return (default 10, max 500)."),
                    "stream", Map.of(
                            "type", "string",
                            "enum", List.of("stdout", "stderr"),
                            "description", "Which stream to tail; default stdout.")),
            "required", List.of("id"));

    private final ExecutionRouter router;

    @Override
    public String name() {
        return "work_exec_tail";
    }

    @Override
    public String description() {
        return "Return the last N lines (default 10, max 500) of a job's "
                + "stdout or stderr log file. Lines come back oldest-first.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawId = params == null ? null : params.get("id");
        if (!(rawId instanceof String id) || id.isBlank()) {
            throw new ToolException("'id' is required");
        }
        int n = DEFAULT_LINES;
        Object rawN = params == null ? null : params.get("n");
        if (rawN instanceof Number num) {
            n = Math.min(MAX_LINES, Math.max(1, num.intValue()));
        }
        String streamName = "stdout";
        Object rawStream = params == null ? null : params.get("stream");
        if (rawStream instanceof String s && "stderr".equalsIgnoreCase(s)) {
            streamName = "stderr";
        }
        return router.tail(id, ctx.tenantId(), n, streamName);
    }
}
