package de.mhus.vance.addon.brain.gtd;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Deterministic counts over a GTD folder: per-bucket sizes, overdue count,
 * per-context and per-project open counts, total open + done. No time-series.
 */
@Component
public class GtdStatsBuilder {

    private final GtdBucketResolver bucketResolver;

    public GtdStatsBuilder(GtdBucketResolver bucketResolver) {
        this.bucketResolver = bucketResolver;
    }

    /**
     * @param bucketCounts  bucket wireName → open count
     * @param overdue       open actions past their when/deadline
     * @param contextCounts context → open count
     * @param projectCounts project → open count
     * @param totalOpen     open (not done) actions
     * @param done          completed actions
     */
    public record Stats(
            Map<String, Integer> bucketCounts,
            int overdue,
            Map<String, Integer> contextCounts,
            Map<String, Integer> projectCounts,
            int totalOpen,
            int done) {}

    public Stats build(GtdFolderReader.Scan scan, LocalDate today) {
        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (GtdBucket b : GtdBucket.values()) buckets.put(b.wireName(), 0);
        Map<String, Integer> contexts = new TreeMap<>();
        Map<String, Integer> projects = new TreeMap<>();
        int overdue = 0;
        int open = 0;
        int done = 0;

        for (GtdAction a : scan.actions()) {
            if (a.done()) { done++; continue; }
            open++;
            GtdBucket bucket = bucketResolver.bucketOf(a.inInbox(), a.when(), a.deadline(), today);
            buckets.merge(bucket.wireName(), 1, Integer::sum);
            if (!a.inInbox() && bucketResolver.isOverdue(a.when(), a.deadline(), today)) overdue++;
            for (String c : a.contexts()) contexts.merge(c, 1, Integer::sum);
            if (a.project() != null) projects.merge(a.project(), 1, Integer::sum);
        }
        return new Stats(buckets, overdue, contexts, projects, open, done);
    }

    /** Convenience for tests / callers that already grouped the buckets. */
    public List<GtdBucket> orderedBuckets() {
        return List.of(GtdBucket.INBOX, GtdBucket.TODAY, GtdBucket.UPCOMING,
                GtdBucket.ANYTIME, GtdBucket.SOMEDAY);
    }
}
