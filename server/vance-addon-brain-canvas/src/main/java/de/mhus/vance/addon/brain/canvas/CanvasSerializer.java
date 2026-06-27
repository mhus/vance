package de.mhus.vance.addon.brain.canvas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link Block} list → Markdown serializer. Counterpart to
 * {@link CanvasParser}. Output rules:
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
public class CanvasSerializer {

    /**
     * Render a full canvas document — YAML front-matter (with
     * {@code $meta.kind: canvas} + optional title/description) followed
     * by the block body. Used for {@link CanvasDocument} persistence.
     */
    public String serializeDocument(CanvasDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("$meta:\n  kind: canvas\n");
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
            case Block.UnknownFence uf -> "```" + uf.infoString() + "\n"
                    + uf.body() + (uf.body().endsWith("\n") ? "" : "\n") + "```\n";
        };
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
