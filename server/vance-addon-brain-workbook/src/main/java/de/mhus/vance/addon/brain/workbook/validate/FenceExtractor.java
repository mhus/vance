package de.mhus.vance.addon.brain.workbook.validate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Extracts {@code ```vance-<type>} fences from a workpage markdown body into
 * {@link FenceBlock}s. Backtick-run aware: a fence opened with N backticks
 * closes on the next line of ≥ N backticks, so nested fences (e.g. a
 * {@code vance-form} inside a 4-backtick {@code vance-columns}) are captured
 * individually. Best-effort by design — malformed YAML is reported per block
 * via {@link FenceBlock#parseError()} rather than aborting the scan.
 */
@Component
public class FenceExtractor {

    // Opening fence: run of ≥3 backticks + "vance-" + a type token.
    private static final Pattern OPEN =
            Pattern.compile("^(`{3,})vance-([a-zA-Z][a-zA-Z0-9-]*)\\s*$");
    // Any fence line (potential close): run of ≥3 backticks, nothing else.
    private static final Pattern FENCE_LINE = Pattern.compile("^(`{3,})\\s*$");

    /**
     * Container fence types whose body holds further {@code vance-*} fences;
     * the extractor recurses into them so nested blocks are validated too.
     */
    private static final Set<String> CONTAINERS = Set.of("columns");

    public List<FenceBlock> extract(String docPath, String body) {
        return extract(docPath, body, 0);
    }

    private List<FenceBlock> extract(String docPath, String body, int lineOffset) {
        List<FenceBlock> blocks = new ArrayList<>();
        if (body == null || body.isEmpty()) return blocks;
        String[] lines = body.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            Matcher open = OPEN.matcher(lines[i]);
            if (!open.matches()) {
                i++;
                continue;
            }
            int fenceLen = open.group(1).length();
            String type = open.group(2);
            int startLine = lineOffset + i + 1; // 1-based
            StringBuilder raw = new StringBuilder();
            int j = i + 1;
            boolean closed = false;
            while (j < lines.length) {
                Matcher close = FENCE_LINE.matcher(lines[j]);
                if (close.matches() && close.group(1).length() >= fenceLen) {
                    closed = true;
                    break;
                }
                raw.append(lines[j]).append('\n');
                j++;
            }
            if (CONTAINERS.contains(type)) {
                // The container itself has no dedicated validator; recurse so
                // the fences it wraps are checked individually.
                blocks.addAll(extract(docPath, raw.toString(), startLine));
            } else {
                blocks.add(toBlock(docPath, type, raw.toString(), startLine));
            }
            // An unterminated fence consumes the rest; stop scanning.
            i = closed ? j + 1 : lines.length;
        }
        return blocks;
    }

    private static FenceBlock toBlock(String docPath, String type, String raw, int line) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        String parseError = null;
        if (!raw.isBlank()) {
            try {
                Object loaded = new Yaml().load(raw);
                if (loaded instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() != null) attrs.put(e.getKey().toString(), e.getValue());
                    }
                } else if (loaded != null) {
                    parseError = "fence body is not a key: value mapping";
                }
            } catch (RuntimeException e) {
                parseError = "invalid YAML: " + e.getMessage();
            }
        }
        return new FenceBlock(type, attrs, raw, docPath, line, parseError);
    }
}
