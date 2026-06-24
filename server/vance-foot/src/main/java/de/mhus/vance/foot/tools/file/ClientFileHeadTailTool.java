package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code head} / {@code tail} on the foot host. Returns the first
 * {@code head} and / or last {@code tail} lines of a file. At least
 * one of the two has to be > 0 — the tool always returns a bounded
 * slice rather than the full body (use {@code client_file_read} for
 * full reads with paging).
 */
@Component
public class ClientFileHeadTailTool implements ClientTool {

    private static final int MAX_LINES = 5_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of("type", "string",
                            "description",
                                    "File path on the foot host. Supports a leading '~/' for home."),
                    "head", Map.of("type", "integer",
                            "description",
                                    "Lines from the top. 0 / omitted = none. Capped at " + MAX_LINES + "."),
                    "tail", Map.of("type", "integer",
                            "description",
                                    "Lines from the bottom. Capped at " + MAX_LINES + ".")),
            "required", List.of("path"));

    @Override public String name() { return "client_file_head_tail"; }
    @Override public String description() {
        return "Return the first N lines (head) and / or last N lines (tail) of "
                + "a file on the user's machine. At least one of head / tail "
                + "must be > 0. Lines carry 1-based numbers.";
    }
    @Override public boolean primary() { return true; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Requires CLIENT target — Foot must be connected. File missing = check path.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem", "client");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        String pathRaw = stringOrThrow(params, "path");
        int head = clampLines(intOrNull(params, "head"));
        int tail = clampLines(intOrNull(params, "tail"));
        if (head == 0 && tail == 0) {
            throw new IllegalArgumentException("At least one of 'head' or 'tail' must be > 0");
        }

        Path file = ClientFilePaths.resolve(pathRaw);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Not found: " + file.toAbsolutePath());
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a regular file: " + file.toAbsolutePath());
        }

        List<String> all;
        try {
            all = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Read failed: " + e.getMessage(), e);
        }
        int total = all.size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.toAbsolutePath().toString());
        out.put("totalLines", total);
        if (head > 0) {
            int n = Math.min(head, total);
            List<Map<String, Object>> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rows.add(Map.of("lineNumber", i + 1, "line", all.get(i)));
            }
            out.put("head", rows);
        }
        if (tail > 0) {
            int n = Math.min(tail, total);
            List<Map<String, Object>> rows = new ArrayList<>(n);
            int start = total - n;
            for (int i = 0; i < n; i++) {
                rows.add(Map.of("lineNumber", start + i + 1, "line", all.get(start + i)));
            }
            out.put("tail", rows);
        }
        return out;
    }

    private static int clampLines(Integer raw) {
        if (raw == null) return 0;
        return Math.min(MAX_LINES, Math.max(0, raw));
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static Integer intOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof Number n ? n.intValue() : null;
    }
}
