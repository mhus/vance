package de.mhus.vance.foot.tools.exec;

import de.mhus.vance.foot.tools.ClientTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Last N lines of a client-side exec job's log file. Mirror of brain
 * {@code exec_tail}.
 */
@Component
@RequiredArgsConstructor
public class ClientExecTailTool implements ClientTool {

    private static final int DEFAULT_LINES = 10;
    private static final int MAX_LINES = 500;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by client_exec_run."),
                    "n", Map.of(
                            "type", "integer",
                            "description", "Number of lines to return (default 10, max 500)."),
                    "stream", Map.of(
                            "type", "string",
                            "enum", List.of("stdout", "stderr"),
                            "description", "Which stream to tail; default stdout.")),
            "required", List.of("id"));

    private final ClientExecutorService executor;

    @Override
    public String name() {
        return "client_exec_tail";
    }

    @Override
    public String description() {
        return "Return the last N lines (default 10, max 500) of a "
                + "client-exec job's stdout or stderr log file. Lines come "
                + "back oldest-first.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read-only");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object rawId = params == null ? null : params.get("id");
        if (!(rawId instanceof String id) || id.isBlank()) {
            throw new IllegalArgumentException("'id' is required");
        }
        int n = DEFAULT_LINES;
        Object rawN = params == null ? null : params.get("n");
        if (rawN instanceof Number num) {
            n = Math.min(MAX_LINES, Math.max(1, num.intValue()));
        }
        ClientExecutorService.Stream stream = ClientExecutorService.Stream.STDOUT;
        Object rawStream = params == null ? null : params.get("stream");
        if (rawStream instanceof String s && "stderr".equalsIgnoreCase(s)) {
            stream = ClientExecutorService.Stream.STDERR;
        }
        List<String> lines = executor.tail(id, n, stream);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("stream", stream.name().toLowerCase());
        out.put("lines", lines);
        out.put("returned", lines.size());
        return out;
    }
}
