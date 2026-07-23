package de.mhus.vance.addon.brain.journal;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders the generated {@code _stats.yaml} — a {@code kind: data} body
 * with the deterministic journal statistics. Read-only output; rewritten
 * on every refresh. Hand-rolled YAML (flat, scalar values only) so the
 * output is stable and diff-friendly.
 */
@Component
public class JournalStatsRenderer {

    public String render(String folder, JournalStatsBuilder.Stats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("$meta:\n  kind: data\n");
        sb.append("folder: ").append(folder).append('\n');
        sb.append("totalEntries: ").append(stats.totalEntries()).append('\n');
        if (stats.firstEntry() != null) sb.append("firstEntry: ").append(stats.firstEntry()).append('\n');
        if (stats.lastEntry() != null) sb.append("lastEntry: ").append(stats.lastEntry()).append('\n');
        sb.append("currentStreak: ").append(stats.currentStreak()).append('\n');
        sb.append("longestStreak: ").append(stats.longestStreak()).append('\n');
        sb.append("entriesThisMonth: ").append(stats.entriesThisMonth()).append('\n');
        sb.append("entriesThisYear: ").append(stats.entriesThisYear()).append('\n');

        sb.append("moodDistribution:");
        if (stats.moodDistribution().isEmpty()) {
            sb.append(" {}\n");
        } else {
            sb.append('\n');
            for (Map.Entry<String, Integer> e : stats.moodDistribution().entrySet()) {
                sb.append("  ").append(yamlKey(e.getKey())).append(": ").append(e.getValue()).append('\n');
            }
        }

        sb.append("tagHistogram:");
        if (stats.tagHistogram().isEmpty()) {
            sb.append(" {}\n");
        } else {
            sb.append('\n');
            for (Map.Entry<String, Integer> e : stats.tagHistogram().entrySet()) {
                sb.append("  ").append(yamlKey(e.getKey())).append(": ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Quote a map key when it isn't a plain scalar. */
    private static String yamlKey(String key) {
        if (key.matches("[A-Za-z0-9_-]+")) return key;
        return "\"" + key.replace("\"", "\\\"") + "\"";
    }
}
