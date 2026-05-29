package de.mhus.vance.brain.tools.transform;

import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import java.util.List;

/**
 * Build a GFM-style Markdown table from a {@link RecordsDocument}.
 * Shared by the records→pdf and records→docx transformers so both
 * pipelines end up at the existing markdown renderers.
 *
 * <p>Cell-content rules:
 * <ul>
 *   <li>{@code |} → escaped as {@code \|} so the pipe doesn't
 *       break the table grid.</li>
 *   <li>Embedded newlines → replaced with a space; GFM tables
 *       can't represent multiline cells inside a row.</li>
 *   <li>Optional title-heading above the table when {@code title}
 *       is non-blank.</li>
 * </ul>
 */
public final class RecordsToMarkdownTable {

    private RecordsToMarkdownTable() {}

    /** Render the records document as a markdown document string
     *  (optional H1 heading + GFM table). */
    public static String render(RecordsDocument records, String title) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("# ").append(title.trim()).append("\n\n");
        }
        appendTable(sb, records);
        return sb.toString();
    }

    private static void appendTable(StringBuilder sb, RecordsDocument records) {
        List<String> schema = records.schema();
        if (schema.isEmpty()) return;

        // Header
        sb.append('|');
        for (String col : schema) {
            sb.append(' ').append(escape(col)).append(" |");
        }
        sb.append('\n');

        // Alignment row (left-align everything for now)
        sb.append('|');
        for (int i = 0; i < schema.size(); i++) {
            sb.append(" --- |");
        }
        sb.append('\n');

        // Body
        for (RecordsItem item : records.items()) {
            sb.append('|');
            for (String col : schema) {
                String v = item.values().getOrDefault(col, "");
                sb.append(' ').append(escape(v)).append(" |");
            }
            sb.append('\n');
        }
    }

    /** GFM-table cell escape: pipes are the only structural char,
     *  newlines collapse to spaces. */
    static String escape(String v) {
        if (v == null || v.isEmpty()) return "";
        return v
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
