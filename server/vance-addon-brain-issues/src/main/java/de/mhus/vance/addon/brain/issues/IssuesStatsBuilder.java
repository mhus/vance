package de.mhus.vance.addon.brain.issues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Deterministic counts over the active issues (archived excluded): open/closed
 * totals, per-label and per-assignee. No time-series.
 */
@Component
public class IssuesStatsBuilder {

    /**
     * @param open       open issue count
     * @param closed     closed issue count
     * @param total      open + closed (active)
     * @param byLabel    label → count (open + closed)
     * @param byAssignee assignee → open count
     */
    public record Stats(
            int open,
            int closed,
            int total,
            Map<String, Integer> byLabel,
            Map<String, Integer> byAssignee) {}

    public Stats build(IssuesFolderReader.Scan scan) {
        int open = 0;
        int closed = 0;
        Map<String, Integer> byLabel = new TreeMap<>();
        Map<String, Integer> byAssignee = new TreeMap<>();
        for (Issue i : scan.issues()) {
            if (i.isOpen()) open++; else closed++;
            for (String l : i.labels()) byLabel.merge(l, 1, Integer::sum);
            if (i.isOpen() && i.assignee() != null) byAssignee.merge(i.assignee(), 1, Integer::sum);
        }
        return new Stats(open, closed, open + closed,
                new LinkedHashMap<>(byLabel), new LinkedHashMap<>(byAssignee));
    }
}
