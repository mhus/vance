package de.mhus.vance.addon.brain.workpage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Markdown → {@link Block} list parser. Hand-rolled, line-based — we
 * only need to recognise the subset of Markdown we serialize back out.
 * No inline-text parsing: a paragraph's raw text travels through as-is
 * and the editor handles bold/italic/links rendering.
 *
 * <p>Round-trip invariant: {@code parse(serialize(blocks)) == blocks}
 * for any block list produced by this codec, modulo whitespace
 * normalisation.
 */
@Component
public class WorkPageParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.+?)\\s*$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+\\.\\s+(.+?)\\s*$");
    private static final Pattern TODO = Pattern.compile("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.+?)\\s*$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    // Fence run is captured so a longer outer fence (vance-columns wraps
    // nested fenced blocks) can be closed only by an equally-long fence.
    private static final Pattern FENCE_OPEN = Pattern.compile("^(`{3,})(\\S*)\\s*$");
    private static final Pattern COLUMN_SEP =
            Pattern.compile("\\n<!--vance:column(?:\\s+([\\d.]+))?-->\\n");
    private static final Pattern DIVIDER = Pattern.compile("^---+\\s*$");
    private static final Pattern IMAGE_ONLY = Pattern.compile("^!\\[(.*?)]\\((.+?)\\)\\s*$");
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?\\s*$");

    private final Yaml yaml = new Yaml();

    /**
     * Parse a full workpage document — YAML front-matter (if present) plus
     * the block body — into a {@link WorkPageDocument}. Missing or invalid
     * front-matter is treated as "no headers"; the body parses regardless.
     */
    public WorkPageDocument parseDocument(String fullMarkdown) {
        if (fullMarkdown == null) return new WorkPageDocument(null, null, List.of());
        String body = fullMarkdown;
        String title = null;
        String description = null;
        if (fullMarkdown.startsWith("---\n")) {
            int end = fullMarkdown.indexOf("\n---\n", 4);
            if (end > 0) {
                String headerText = fullMarkdown.substring(4, end);
                try {
                    Object loaded = yaml.load(headerText);
                    if (loaded instanceof java.util.Map<?, ?> m) {
                        Object t = m.get("title");
                        if (t != null) title = t.toString();
                        Object d = m.get("description");
                        if (d != null) description = d.toString();
                    }
                } catch (RuntimeException ignored) { /* fall through */ }
                body = fullMarkdown.substring(end + 5);
            }
        }
        return new WorkPageDocument(title, description, parse(body));
    }

    /** Parse the Markdown body into a block list. Never returns null. */
    public List<Block> parse(String markdown) {
        List<String> lines = splitLines(markdown);
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);

            // Skip blank lines between blocks.
            if (line.isBlank()) { i++; continue; }

            // Fenced block?
            Matcher mFence = FENCE_OPEN.matcher(line);
            if (mFence.matches()) {
                int fenceLen = mFence.group(1).length();
                String info = mFence.group(2);
                int end = findFenceClose(lines, i + 1, fenceLen);
                String body = String.join("\n", lines.subList(i + 1, end));
                blocks.add(parseFence(info, body));
                i = end + 1;
                continue;
            }

            // Heading.
            Matcher mH = HEADING.matcher(line);
            if (mH.matches()) {
                blocks.add(new Block.Heading(mH.group(1).length(), mH.group(2)));
                i++;
                continue;
            }

            // Divider.
            if (DIVIDER.matcher(line).matches()) {
                blocks.add(new Block.Divider());
                i++;
                continue;
            }

            // Image as its own block (image on its own line).
            Matcher mImg = IMAGE_ONLY.matcher(line);
            if (mImg.matches()) {
                blocks.add(new Block.Image(mImg.group(1), mImg.group(2)));
                i++;
                continue;
            }

            // Todo list — check before generic bullet because `- [ ]`
            // also matches BULLET.
            if (TODO.matcher(line).matches()) {
                List<Block.TodoItem> items = new ArrayList<>();
                while (i < lines.size()) {
                    Matcher m = TODO.matcher(lines.get(i));
                    if (!m.matches()) break;
                    boolean checked = !m.group(1).equals(" ");
                    items.add(new Block.TodoItem(checked, m.group(2)));
                    i++;
                }
                blocks.add(new Block.TodoList(items));
                continue;
            }

            // Bullet list.
            if (BULLET.matcher(line).matches() && !TODO.matcher(line).matches()) {
                List<String> items = new ArrayList<>();
                while (i < lines.size()) {
                    Matcher m = BULLET.matcher(lines.get(i));
                    if (!m.matches() || TODO.matcher(lines.get(i)).matches()) break;
                    items.add(m.group(1));
                    i++;
                }
                blocks.add(new Block.BulletList(items));
                continue;
            }

            // Numbered list.
            if (NUMBERED.matcher(line).matches()) {
                List<String> items = new ArrayList<>();
                while (i < lines.size()) {
                    Matcher m = NUMBERED.matcher(lines.get(i));
                    if (!m.matches()) break;
                    items.add(m.group(1));
                    i++;
                }
                blocks.add(new Block.NumberedList(items));
                continue;
            }

            // Blockquote.
            if (QUOTE.matcher(line).matches()) {
                List<String> quoteLines = new ArrayList<>();
                while (i < lines.size()) {
                    Matcher m = QUOTE.matcher(lines.get(i));
                    if (!m.matches()) break;
                    quoteLines.add(m.group(1));
                    i++;
                }
                blocks.add(new Block.Quote(String.join("\n", quoteLines)));
                continue;
            }

            // Table — heuristic: pipe-bearing line followed by divider.
            if (line.contains("|") && i + 1 < lines.size()
                    && TABLE_DIVIDER.matcher(lines.get(i + 1)).matches()) {
                Block.Table tbl = parseTable(lines, i);
                blocks.add(tbl);
                i += 2 + tbl.rows().size();
                continue;
            }

            // Paragraph: collect consecutive non-blank, non-special lines.
            List<String> paraLines = new ArrayList<>();
            while (i < lines.size()) {
                String l = lines.get(i);
                if (l.isBlank() || isBlockStart(l, i + 1 < lines.size() ? lines.get(i + 1) : ""))
                    break;
                paraLines.add(l);
                i++;
            }
            if (!paraLines.isEmpty()) {
                blocks.add(new Block.Paragraph(String.join("\n", paraLines)));
            }
        }
        return blocks;
    }

    private int findFenceClose(List<String> lines, int from, int fenceLen) {
        String close = "`".repeat(fenceLen);
        for (int j = from; j < lines.size(); j++) {
            if (lines.get(j).trim().equals(close)) return j;
        }
        return lines.size();
    }

    private boolean isBlockStart(String line, String next) {
        if (HEADING.matcher(line).matches()) return true;
        if (BULLET.matcher(line).matches()) return true;
        if (NUMBERED.matcher(line).matches()) return true;
        if (TODO.matcher(line).matches()) return true;
        if (QUOTE.matcher(line).matches()) return true;
        if (FENCE_OPEN.matcher(line).matches()) return true;
        if (DIVIDER.matcher(line).matches()) return true;
        if (IMAGE_ONLY.matcher(line).matches()) return true;
        if (line.contains("|") && TABLE_DIVIDER.matcher(next).matches()) return true;
        return false;
    }

    private Block parseFence(String info, String body) {
        if (info == null || info.isBlank() || !info.startsWith("vance-")) {
            return new Block.Code(info == null || info.isBlank() ? null : info, body);
        }
        // Markdown-body kinds — handle BEFORE the YAML parse: their bodies
        // legitimately carry nested fences (columns) or nothing (toc),
        // which would make yaml.load() throw and drop to unknown-fence.
        if (info.equals("vance-toc")) return new Block.Toc();
        if (info.equals("vance-columns")) return parseColumns(body);

        Map<String, Object> yamlBody;
        try {
            Object parsed = yaml.load(body);
            yamlBody = parsed instanceof Map<?, ?> m ? coerceMap(m) : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            return new Block.UnknownFence(info, body);
        }
        return switch (info) {
            case "vance-callout" -> new Block.Callout(
                    str(yamlBody, "severity", "info"),
                    str(yamlBody, "title", null),
                    str(yamlBody, "body", ""));
            case "vance-toggle" -> new Block.Toggle(
                    str(yamlBody, "summary", ""),
                    str(yamlBody, "body", ""));
            case "vance-dataview" -> new Block.DataviewEmbed(
                    str(yamlBody, "source", ""));
            case "vance-link" -> new Block.LinkCard(
                    str(yamlBody, "href", ""),
                    str(yamlBody, "title", null),
                    str(yamlBody, "description", null));
            case "vance-embed" -> new Block.Embed(str(yamlBody, "uri", ""));
            case "vance-form" -> new Block.Form(str(yamlBody, "config", ""));
            case "vance-input" -> new Block.Input(
                    str(yamlBody, "config", ""),
                    boolVal(yamlBody, "multiline"));
            default -> new Block.UnknownFence(info, body);
        };
    }

    /**
     * Split a {@code vance-columns} body on the {@code <!--vance:column
     * [width]-->} separators and parse each column's blocks recursively.
     * Mirrors the TS parser.
     */
    private Block parseColumns(String body) {
        Matcher m = COLUMN_SEP.matcher(body);
        List<String> parts = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        widths.add(null);   // first column carries no explicit width
        int last = 0;
        while (m.find()) {
            parts.add(body.substring(last, m.start()));
            widths.add(parseWidth(m.group(1)));
            last = m.end();
        }
        parts.add(body.substring(last));
        List<Block.Column> cols = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            cols.add(new Block.Column(widths.get(i), parse(parts.get(i).trim())));
        }
        return new Block.Columns(cols);
    }

    private static @org.jspecify.annotations.Nullable Double parseWidth(
            @org.jspecify.annotations.Nullable String raw) {
        if (raw == null) return null;
        try {
            double d = Double.parseDouble(raw);
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean boolVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    private Block.Table parseTable(List<String> lines, int start) {
        List<String> headers = splitTableRow(lines.get(start));
        // skip divider at start+1
        List<List<String>> rows = new ArrayList<>();
        int i = start + 2;
        while (i < lines.size()) {
            String l = lines.get(i);
            if (l.isBlank() || !l.contains("|")) break;
            rows.add(splitTableRow(l));
            i++;
        }
        return new Block.Table(headers, rows);
    }

    private List<String> splitTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return Arrays.stream(trimmed.split("\\|", -1))
                .map(String::trim)
                .toList();
    }

    private static List<String> splitLines(String s) {
        if (s == null || s.isEmpty()) return List.of();
        // Normalise CRLF / CR to LF, then split keeping empties.
        String n = s.replace("\r\n", "\n").replace('\r', '\n');
        return new ArrayList<>(Arrays.asList(n.split("\n", -1)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        if (v == null) return fallback;
        return v.toString();
    }
}
