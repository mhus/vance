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
 * {@code maxLines} you can page through large files; without them
 * the response is capped at {@value #DEFAULT_CHAR_CAP} characters
 * and {@code truncated=true} signals that the LLM should page.
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
                            "description", "Maximum lines to return. Omit for the default char cap.")),
            "required", List.of("path"));

    @Override
    public String name() {
        return "client_file_read";
    }

    @Override
    public String description() {
        return "Read a text file on the user's machine (foot host). "
                + "Use startLine + maxLines to page large files; "
                + "without them the result is capped at ~8 000 chars.";
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
    public Map<String, Object> invoke(Map<String, Object> params) {
        String path = stringOrThrow(params, "path");
        Integer startLine = integerOrNull(params, "startLine");
        Integer maxLines = integerOrNull(params, "maxLines");
        Path p = ClientFilePaths.resolve(path);
        try {
            String content;
            boolean truncated = false;
            int totalChars;
            if (startLine != null || maxLines != null) {
                int from = startLine == null ? 1 : Math.max(1, startLine);
                int count = maxLines == null ? Integer.MAX_VALUE : Math.max(0, maxLines);
                try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
                    content = lines.skip(from - 1)
                            .limit(count)
                            .collect(Collectors.joining("\n"));
                }
                totalChars = content.length();
            } else {
                String full = Files.readString(p, StandardCharsets.UTF_8);
                totalChars = full.length();
                if (full.length() > DEFAULT_CHAR_CAP) {
                    content = full.substring(0, DEFAULT_CHAR_CAP);
                    truncated = true;
                } else {
                    content = full;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", p.toAbsolutePath().toString());
            out.put("content", content);
            out.put("truncated", truncated);
            out.put("totalChars", totalChars);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Read failed: " + e.getMessage(), e);
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
