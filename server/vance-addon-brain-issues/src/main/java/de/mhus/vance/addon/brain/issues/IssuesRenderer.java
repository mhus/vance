package de.mhus.vance.addon.brain.issues;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders the generated artefacts: {@code _index.md} (open issues + recently
 * closed) and {@code _stats.yaml}. Read-only, rewritten on every refresh.
 * Archived issues are not part of either (they left the active tracker).
 */
@Component
public class IssuesRenderer {

    private static final int RECENT_CLOSED = 10;

    public String renderIndex(IssuesFolderReader.Scan scan, String title) {
        List<Issue> issues = scan.issues();
        StringBuilder sb = new StringBuilder();
        sb.append("---\nkind: text\ntitle: \"").append(escape("Issues — " + title)).append("\"\n---\n\n");
        sb.append("# ").append(title).append("\n\n");

        List<Issue> open = issues.stream().filter(Issue::isOpen).toList();
        List<Issue> closed = issues.stream().filter(i -> !i.isOpen()).toList();

        sb.append("## Open (").append(open.size()).append(")\n\n");
        if (open.isEmpty()) sb.append("_No open issues._\n");
        else for (Issue i : open) sb.append(line(i));

        if (!closed.isEmpty()) {
            sb.append("\n## Recently closed\n\n");
            closed.stream().limit(RECENT_CLOSED).forEach(i -> sb.append(line(i)));
        }
        return sb.toString();
    }

    public String renderStats(String folder, IssuesStatsBuilder.Stats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("$meta:\n  kind: data\n");
        sb.append("folder: ").append(folder).append('\n');
        sb.append("open: ").append(stats.open()).append('\n');
        sb.append("closed: ").append(stats.closed()).append('\n');
        sb.append("total: ").append(stats.total()).append('\n');
        sb.append("byLabel:");
        appendMap(sb, stats.byLabel());
        sb.append("byAssignee:");
        appendMap(sb, stats.byAssignee());
        return sb.toString();
    }

    private static void appendMap(StringBuilder sb, Map<String, Integer> m) {
        if (m.isEmpty()) { sb.append(" {}\n"); return; }
        sb.append('\n');
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            sb.append("  ").append(yamlKey(e.getKey())).append(": ").append(e.getValue()).append('\n');
        }
    }

    private static String line(Issue i) {
        StringBuilder b = new StringBuilder();
        b.append("- **#").append(i.number()).append("** ").append(i.title());
        if (i.assignee() != null) b.append(" _(@").append(i.assignee()).append(")_");
        for (String l : i.labels()) b.append("  `").append(l).append('`');
        b.append('\n');
        return b.toString();
    }

    private static String yamlKey(String key) {
        return key.matches("[A-Za-z0-9_@.-]+") ? key : "\"" + key.replace("\"", "\\\"") + "\"";
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
