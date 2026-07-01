package de.mhus.vance.addon.brain.workpage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link Block} list → Markdown serializer. Counterpart to
 * {@link WorkPageParser}. Output rules:
 *
 * <ul>
 *   <li>One blank line between blocks.</li>
 *   <li>{@code vance-*} fences carry flat YAML headers; multi-line
 *       strings (toggle bodies) use literal-block style ({@code |}).</li>
 *   <li>{@link Block.UnknownFence} is round-tripped verbatim — never
 *       dropped — so a future fence type added by a newer client
 *       survives an old-client save.</li>
 * </ul>
 */
@Component
public class WorkPageSerializer {

    /**
     * Render a full workpage document — YAML front-matter (with
     * {@code $meta.kind: workpage} + optional title/description) followed
     * by the block body. Used for {@link WorkPageDocument} persistence.
     */
    public String serializeDocument(WorkPageDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("$meta:\n  kind: workpage\n");
        if (doc.title() != null && !doc.title().isBlank()) {
            sb.append("title: ").append(escapeYaml(doc.title())).append("\n");
        }
        if (doc.description() != null && !doc.description().isBlank()) {
            sb.append("description: ").append(escapeYaml(doc.description())).append("\n");
        }
        sb.append("---\n");
        sb.append(serialize(doc.blocks()));
        return sb.toString();
    }

    private static String escapeYaml(String value) {
        if (value.contains("\n") || value.contains(":") || value.contains("#")
                || value.contains("\"") || value.contains("'")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    /** Render a block list as Markdown. */
    public String serialize(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(renderBlock(blocks.get(i)));
            if (!sb.toString().endsWith("\n")) sb.append("\n");
        }
        return sb.toString();
    }

    private String renderBlock(Block block) {
        return switch (block) {
            case Block.Paragraph p -> p.text() + "\n";
            case Block.Heading h -> "#".repeat(h.level()) + " " + h.text() + "\n";
            case Block.BulletList b -> {
                StringBuilder s = new StringBuilder();
                for (String item : b.items()) s.append("- ").append(item).append("\n");
                yield s.toString();
            }
            case Block.NumberedList n -> {
                StringBuilder s = new StringBuilder();
                int idx = 1;
                for (String item : n.items()) {
                    s.append(idx++).append(". ").append(item).append("\n");
                }
                yield s.toString();
            }
            case Block.TodoList t -> {
                StringBuilder s = new StringBuilder();
                for (Block.TodoItem item : t.items()) {
                    s.append("- [").append(item.checked() ? "x" : " ")
                            .append("] ").append(item.text()).append("\n");
                }
                yield s.toString();
            }
            case Block.Quote q -> {
                StringBuilder s = new StringBuilder();
                for (String line : q.text().split("\n", -1)) {
                    s.append("> ").append(line).append("\n");
                }
                yield s.toString();
            }
            case Block.Code c -> "```" + (c.lang() == null ? "" : c.lang()) + "\n"
                    + c.code() + (c.code().endsWith("\n") ? "" : "\n") + "```\n";
            case Block.Divider ignored -> "---\n";
            case Block.Image img -> "![" + img.alt() + "](" + img.src() + ")\n";
            case Block.Table tbl -> renderTable(tbl);
            case Block.Callout co -> renderFence("vance-callout", new LinkedHashMap<>() {{
                put("severity", co.severity());
                if (co.title() != null) put("title", co.title());
                if (co.body() != null && !co.body().isEmpty()) put("body", co.body());
            }});
            case Block.Toggle tg -> renderFence("vance-toggle", new LinkedHashMap<>() {{
                put("summary", tg.summary());
                put("body", tg.body());
            }});
            case Block.DataviewEmbed dv -> renderFence("vance-dataview", new LinkedHashMap<>() {{
                put("source", dv.source());
            }});
            case Block.LinkCard lc -> renderFence("vance-link", new LinkedHashMap<>() {{
                put("href", lc.href());
                if (lc.title() != null) put("title", lc.title());
                if (lc.description() != null) put("description", lc.description());
            }});
            case Block.Embed em -> renderFence("vance-embed", new LinkedHashMap<>() {{
                put("uri", em.uri());
            }});
            case Block.Form fo -> renderFence("vance-form", new LinkedHashMap<>() {{
                put("config", fo.config());
            }});
            case Block.Input in -> renderFence("vance-input", new LinkedHashMap<>() {{
                put("config", in.config());
                put("multiline", in.multiline());
            }});
            case Block.Toc ignored -> "```vance-toc\n```\n";
            case Block.Columns cols -> renderColumns(cols);
            case Block.UnknownFence uf -> "```" + uf.infoString() + "\n"
                    + uf.body() + (uf.body().endsWith("\n") ? "" : "\n") + "```\n";
        };
    }

    /**
     * Render a {@code vance-columns} block. The outer fence is one
     * backtick longer than the longest fence appearing in any column
     * body, so nested code / {@code vance-*} / sub-column blocks don't
     * close it early. Columns are separated by an HTML-comment marker
     * carrying the optional relative width. Mirrors the TS serializer.
     */
    private String renderColumns(Block.Columns cols) {
        List<String> innerBodies = new ArrayList<>();
        int innerMaxFence = 3;
        for (Block.Column col : cols.columns()) {
            String body = serialize(col.blocks());
            innerBodies.add(body);
            innerMaxFence = Math.max(innerMaxFence, maxFenceLength(body));
        }
        String fence = "`".repeat(innerMaxFence + 1);
        StringBuilder out = new StringBuilder(fence).append("vance-columns\n");
        for (int i = 0; i < cols.columns().size(); i++) {
            Block.Column col = cols.columns().get(i);
            if (i > 0) {
                out.append(col.width() != null
                        ? "\n<!--vance:column " + formatWidth(col.width()) + "-->\n"
                        : "\n<!--vance:column-->\n");
            }
            out.append(innerBodies.get(i));
        }
        if (out.charAt(out.length() - 1) != '\n') out.append("\n");
        out.append(fence).append("\n");
        return out.toString();
    }

    /** Longest run of leading backticks over all lines (min sensible 0). */
    private static int maxFenceLength(String text) {
        int max = 0;
        for (String line : text.split("\n", -1)) {
            int n = 0;
            while (n < line.length() && line.charAt(n) == '`') n++;
            if (n >= 3 && n > max) max = n;
        }
        return max;
    }

    /** Compact width formatting — {@code 0.4}, {@code 1} (no trailing {@code .0}). */
    private static String formatWidth(double w) {
        if (w == Math.rint(w) && !Double.isInfinite(w)) {
            return Long.toString((long) w);
        }
        return Double.toString(w);
    }

    private String renderTable(Block.Table tbl) {
        StringBuilder s = new StringBuilder();
        s.append("| ").append(String.join(" | ", tbl.headers())).append(" |\n");
        StringBuilder div = new StringBuilder("| ");
        for (int i = 0; i < tbl.headers().size(); i++) {
            if (i > 0) div.append(" | ");
            div.append("---");
        }
        div.append(" |\n");
        s.append(div);
        for (List<String> row : tbl.rows()) {
            s.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return s.toString();
    }

    private String renderFence(String info, Map<String, Object> body) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        opts.setSplitLines(false);
        opts.setIndent(2);
        Yaml yaml = new Yaml(opts);
        String dumped = yaml.dump(body);
        if (!dumped.endsWith("\n")) dumped = dumped + "\n";
        return "```" + info + "\n" + dumped + "```\n";
    }
}
