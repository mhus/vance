package de.mhus.vance.addon.brain.gtd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders the generated artefacts: {@code _today.md} (Today + overdue,
 * grouped by context), {@code _upcoming.md} (chronological) and
 * {@code _stats.yaml}. Read-only outputs, rewritten on every refresh.
 */
@Component
public class GtdRenderer {

    public String renderToday(List<GtdAction> today, List<GtdAction> overdue, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\nkind: text\ntitle: \"").append(escape("Today — " + title)).append("\"\n---\n\n");
        sb.append("# Today\n\n");
        if (!overdue.isEmpty()) {
            sb.append("## Overdue\n\n");
            for (GtdAction a : overdue) sb.append(line(a));
            sb.append('\n');
        }
        if (today.isEmpty()) {
            sb.append("_Nothing scheduled for today._\n");
            return sb.toString();
        }
        Map<String, List<GtdAction>> byContext = groupByContext(today);
        for (Map.Entry<String, List<GtdAction>> e : byContext.entrySet()) {
            sb.append("## ").append(e.getKey()).append("\n\n");
            for (GtdAction a : e.getValue()) sb.append(line(a));
            sb.append('\n');
        }
        return sb.toString();
    }

    public String renderUpcoming(List<GtdAction> upcoming, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\nkind: text\ntitle: \"").append(escape("Upcoming — " + title)).append("\"\n---\n\n");
        sb.append("# Upcoming\n\n");
        if (upcoming.isEmpty()) {
            sb.append("_Nothing scheduled ahead._\n");
            return sb.toString();
        }
        List<GtdAction> sorted = new ArrayList<>(upcoming);
        sorted.sort((a, b) -> a.when().compareTo(b.when()));
        String currentDate = null;
        for (GtdAction a : sorted) {
            if (!a.when().equals(currentDate)) {
                currentDate = a.when();
                sb.append("### ").append(currentDate).append("\n\n");
            }
            sb.append(line(a));
        }
        return sb.toString();
    }

    public String renderStats(String folder, GtdStatsBuilder.Stats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("$meta:\n  kind: data\n");
        sb.append("folder: ").append(folder).append('\n');
        sb.append("totalOpen: ").append(stats.totalOpen()).append('\n');
        sb.append("done: ").append(stats.done()).append('\n');
        sb.append("overdue: ").append(stats.overdue()).append('\n');
        sb.append("bucketCounts:\n");
        for (Map.Entry<String, Integer> e : stats.bucketCounts().entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append("contextCounts:");
        appendMap(sb, stats.contextCounts());
        sb.append("projectCounts:");
        appendMap(sb, stats.projectCounts());
        return sb.toString();
    }

    private static void appendMap(StringBuilder sb, Map<String, Integer> m) {
        if (m.isEmpty()) { sb.append(" {}\n"); return; }
        sb.append('\n');
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            sb.append("  ").append(yamlKey(e.getKey())).append(": ").append(e.getValue()).append('\n');
        }
    }

    private static Map<String, List<GtdAction>> groupByContext(List<GtdAction> actions) {
        Map<String, List<GtdAction>> map = new LinkedHashMap<>();
        for (GtdAction a : actions) {
            String key = a.contexts().isEmpty() ? "No context" : a.contexts().get(0);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        return map;
    }

    private static String line(GtdAction a) {
        StringBuilder b = new StringBuilder();
        b.append("- ").append(a.title());
        if (a.deadline() != null) b.append(" _(due ").append(a.deadline()).append(")_");
        if (a.contexts().size() > 1) {
            b.append("  ").append(String.join(" ", a.contexts()));
        }
        b.append('\n');
        return b.toString();
    }

    private static String yamlKey(String key) {
        return key.matches("[A-Za-z0-9_@-]+") ? key : "\"" + key.replace("\"", "\\\"") + "\"";
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
