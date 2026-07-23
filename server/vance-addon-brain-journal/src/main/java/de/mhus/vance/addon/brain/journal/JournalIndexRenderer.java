package de.mhus.vance.addon.brain.journal;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders the generated {@code _index.md} — a {@code kind: text} page
 * listing the most recent entries followed by the full entry list grouped
 * by year (newest first). Read-only output; rewritten on every refresh.
 */
@Component
public class JournalIndexRenderer {

    public String render(JournalFolderReader.Scan scan, String title, int recentLimit) {
        List<JournalEntry> entries = scan.entries(); // newest date first
        StringBuilder sb = new StringBuilder();
        sb.append("---\nkind: text\ntitle: \"")
                .append(escape("Index — " + title))
                .append("\"\n---\n\n");
        sb.append("# ").append(title).append("\n\n");

        if (entries.isEmpty()) {
            sb.append("_No entries yet._\n");
            return sb.toString();
        }

        int recent = Math.max(1, recentLimit);
        sb.append("## Recent\n\n");
        for (int i = 0; i < Math.min(recent, entries.size()); i++) {
            sb.append(line(entries.get(i)));
        }

        sb.append("\n## All entries\n\n");
        String currentYear = null;
        for (JournalEntry e : entries) {
            String year = e.date().length() >= 4 ? e.date().substring(0, 4) : "?";
            if (!year.equals(currentYear)) {
                currentYear = year;
                sb.append("\n### ").append(year).append("\n\n");
            }
            sb.append(line(e));
        }
        return sb.toString();
    }

    private static String line(JournalEntry e) {
        StringBuilder b = new StringBuilder();
        b.append("- **").append(e.date()).append("** — ").append(e.title());
        if (e.mood() != null && !e.mood().isBlank()) {
            b.append(" _(").append(e.mood()).append(")_");
        }
        if (!e.tags().isEmpty()) {
            b.append("  ").append(String.join(" ", e.tags().stream()
                    .map(t -> "#" + t).toList()));
        }
        b.append('\n');
        return b.toString();
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
