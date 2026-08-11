package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Reads a UTF-8 file from the foot host. With {@code startLine} /
 * {@code maxLines} you can page through large files; the response is
 * capped at {@code maxChars} (default {@value #DEFAULT_CHAR_CAP}) and
 * {@code truncated=true} signals that the LLM should page.
 *
 * <p>{@code totalChars} describes the <em>requested region</em>: the
 * whole file for an unwindowed read, the selected line window
 * otherwise. That keeps {@code truncated} and {@code totalChars}
 * talking about the same thing.
 */
@Component
public class ClientFileReadTool implements ClientTool {

    private static final int DEFAULT_CHAR_CAP = 8_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Absolute or working-dir relative path on the foot host."),
                    "startLine", Map.of(
                            "type", "integer",
                            "description", "1-based start line. Omit to start from the beginning."),
                    "maxLines", Map.of(
                            "type", "integer",
                            "description", "Maximum lines to return. Omit for the default char cap."),
                    "maxChars", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum characters to return. 0 or negative means "
                                            + "the default cap of " + DEFAULT_CHAR_CAP + ".")),
            "required", List.of("path"));

    @Override
    public String name() {
        return "client_file_read";
    }

    @Override
    public String description() {
        return "Read a text file on the user's machine (foot host). "
                + "Use startLine + maxLines to page large files; the result "
                + "is capped at maxChars (default ~8 000).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Explicit CLIENT variant of file_read — targets the user's machine (foot host) regardless of the work target. Prefer file_read.";
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read-only");
    }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Requires CLIENT target — Foot must be connected. File missing/permission denied = check path; large file = page with startLine + maxLines.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem", "client");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        String path = stringOrThrow(params, "path");
        Integer startLine = integerOrNull(params, "startLine");
        Integer maxLines = integerOrNull(params, "maxLines");
        Integer maxChars = integerOrNull(params, "maxChars");
        int cap = maxChars != null && maxChars > 0 ? maxChars : DEFAULT_CHAR_CAP;
        Path p = ClientFilePaths.resolve(path);
        try {
            // Read the requested region — a line window when asked for, the
            // whole file otherwise — then cap it. The cap applies to both
            // paths: a wide line window is just as capable of returning a
            // megabyte as an uncapped whole-file read.
            String region;
            if (startLine != null || maxLines != null) {
                int from = startLine == null ? 1 : Math.max(1, startLine);
                int count = maxLines == null ? Integer.MAX_VALUE : Math.max(0, maxLines);
                try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
                    region = lines.skip(from - 1)
                            .limit(count)
                            .collect(Collectors.joining("\n"));
                }
            } else {
                region = Files.readString(p, StandardCharsets.UTF_8);
            }
            int totalChars = region.length();
            boolean truncated = totalChars > cap;
            String content = truncated ? region.substring(0, cap) : region;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", ClientFilePaths.toToolPath(p));
            out.put("content", content);
            out.put("truncated", truncated);
            out.put("totalChars", totalChars);
            return out;
        } catch (Exception e) {
            throw new RuntimeException(ClientFilePaths.describeFailure(p, e), e);
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static Integer integerOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Number n) return n.intValue();
        return null;
    }
}
