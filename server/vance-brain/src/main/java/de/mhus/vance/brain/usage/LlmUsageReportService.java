package de.mhus.vance.brain.usage;

import de.mhus.vance.api.insights.UsageBucketDto;
import de.mhus.vance.api.insights.UsageReportDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.SortOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Read-side of the usage ledger. Runs Mongo {@code $group} pipelines
 * over {@code llm_usage_records} to produce bucketed summaries:
 *
 * <ul>
 *   <li>{@link #summary} — time series by day / week / month, optional
 *       project filter;
 *   <li>{@link #byProject} — tenant-wide totals per project;
 *   <li>{@link #byModel} — tenant-wide totals per concrete model.
 * </ul>
 *
 * <p>Each bucket also carries the currency, because the underlying
 * rows can mix currencies (Cortecs/EUR + Anthropic/USD on the same
 * tenant). The aggregation groups by {@code (timeBucket, currency)}
 * or {@code (key, currency)} so the report can show one series per
 * currency without losing the breakdown.
 *
 * <p>Live aggregation is fast enough for v1 — {@code llm_usage_records}
 * is index-backed on {@code (tenantId, createdAt)} and the rows are
 * small. A roll-up collection would only be needed once the row count
 * climbs into the tens of millions per tenant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmUsageReportService {

    private final MongoTemplate mongoTemplate;

    /**
     * Time-bucketed summary. {@code groupBy} accepts {@code day} /
     * {@code week} / {@code month}; anything else falls back to
     * {@code day}.
     */
    public UsageReportDto summary(
            String tenantId,
            Instant from,
            Instant to,
            String groupBy,
            @Nullable String projectId) {

        TimeBucket bucket = TimeBucket.parse(groupBy);
        MatchOperation match = matchTenantWindow(tenantId, from, to, projectId);

        // $group key: { y, m, d (week or day or month start), currency }.
        // We use $dateTrunc to snap createdAt to the bucket start —
        // Mongo 5.0+, available in our Spring Data Mongo 5.x stack.
        Document trunc = new Document("$dateTrunc", new Document()
                .append("date", "$createdAt")
                .append("unit", bucket.unit())
                .append("binSize", 1));
        Document groupKey = new Document()
                .append("ts", trunc)
                .append("currency", "$currency");

        Document group = new Document("$group", new Document()
                .append("_id", groupKey)
                .append("tokensIn", new Document("$sum", "$tokensIn"))
                .append("tokensOut", new Document("$sum", "$tokensOut"))
                .append("cacheReadTokens", new Document("$sum", "$cacheReadTokens"))
                .append("cacheWriteTokens", new Document("$sum", "$cacheWriteTokens"))
                .append("costInput", new Document("$sum", "$costInput"))
                .append("costOutput", new Document("$sum", "$costOutput"))
                .append("costCacheRead", new Document("$sum", "$costCacheRead"))
                .append("costCacheWrite", new Document("$sum", "$costCacheWrite"))
                .append("costTotal", new Document("$sum", "$costTotal"))
                .append("calls", new Document("$sum", 1L)));

        List<UsageBucketDto> rows = runPipeline(
                match,
                group,
                Sort.by(Sort.Order.asc("_id.ts")),
                doc -> {
                    Document key = doc.get("_id", Document.class);
                    return UsageBucketDto.builder()
                            .bucketStart(key.getDate("ts").toInstant())
                            .currency(asString(key.get("currency"), "?"))
                            .tokensIn(asLong(doc.get("tokensIn")))
                            .tokensOut(asLong(doc.get("tokensOut")))
                            .cacheReadTokens(asLong(doc.get("cacheReadTokens")))
                            .cacheWriteTokens(asLong(doc.get("cacheWriteTokens")))
                            .costInput(asDouble(doc.get("costInput")))
                            .costOutput(asDouble(doc.get("costOutput")))
                            .costCacheRead(asDouble(doc.get("costCacheRead")))
                            .costCacheWrite(asDouble(doc.get("costCacheWrite")))
                            .costTotal(asDouble(doc.get("costTotal")))
                            .calls(asLong(doc.get("calls")))
                            .build();
                });

        return UsageReportDto.builder()
                .from(from)
                .to(to)
                .bucketBy(bucket.label())
                .buckets(rows)
                .build();
    }

    public UsageReportDto byProject(String tenantId, Instant from, Instant to) {
        return groupedByKey(tenantId, from, to, "projectId", "project");
    }

    public UsageReportDto byModel(String tenantId, Instant from, Instant to) {
        return groupedByKey(tenantId, from, to, "providerModel", "model");
    }

    private UsageReportDto groupedByKey(
            String tenantId,
            Instant from,
            Instant to,
            String keyField,
            String label) {

        MatchOperation match = matchTenantWindow(tenantId, from, to, /*projectId*/ null);
        Document groupKey = new Document()
                .append("key", "$" + keyField)
                .append("currency", "$currency");
        Document group = new Document("$group", new Document()
                .append("_id", groupKey)
                .append("tokensIn", new Document("$sum", "$tokensIn"))
                .append("tokensOut", new Document("$sum", "$tokensOut"))
                .append("cacheReadTokens", new Document("$sum", "$cacheReadTokens"))
                .append("cacheWriteTokens", new Document("$sum", "$cacheWriteTokens"))
                .append("costInput", new Document("$sum", "$costInput"))
                .append("costOutput", new Document("$sum", "$costOutput"))
                .append("costCacheRead", new Document("$sum", "$costCacheRead"))
                .append("costCacheWrite", new Document("$sum", "$costCacheWrite"))
                .append("costTotal", new Document("$sum", "$costTotal"))
                .append("calls", new Document("$sum", 1L)));

        List<UsageBucketDto> rows = runPipeline(
                match,
                group,
                Sort.by(Sort.Order.desc("costTotal")),
                doc -> {
                    Document key = doc.get("_id", Document.class);
                    return UsageBucketDto.builder()
                            .key(asString(key.get("key"), "?"))
                            .currency(asString(key.get("currency"), "?"))
                            .tokensIn(asLong(doc.get("tokensIn")))
                            .tokensOut(asLong(doc.get("tokensOut")))
                            .cacheReadTokens(asLong(doc.get("cacheReadTokens")))
                            .cacheWriteTokens(asLong(doc.get("cacheWriteTokens")))
                            .costInput(asDouble(doc.get("costInput")))
                            .costOutput(asDouble(doc.get("costOutput")))
                            .costCacheRead(asDouble(doc.get("costCacheRead")))
                            .costCacheWrite(asDouble(doc.get("costCacheWrite")))
                            .costTotal(asDouble(doc.get("costTotal")))
                            .calls(asLong(doc.get("calls")))
                            .build();
                });

        return UsageReportDto.builder()
                .from(from)
                .to(to)
                .bucketBy(label)
                .buckets(rows)
                .build();
    }

    private MatchOperation matchTenantWindow(
            String tenantId, Instant from, Instant to, @Nullable String projectId) {
        Criteria c = Criteria.where("tenantId").is(tenantId)
                .and("createdAt").gte(from).lt(to);
        if (projectId != null && !projectId.isBlank()) {
            c = c.and("projectId").is(projectId);
        }
        return Aggregation.match(c);
    }

    private List<UsageBucketDto> runPipeline(
            MatchOperation match,
            Document group,
            Sort sort,
            java.util.function.Function<Document, UsageBucketDto> mapper) {

        AggregationOperation rawGroup = ctx -> group;
        SortOperation sortOp = Aggregation.sort(sort);
        Aggregation pipeline = Aggregation.newAggregation(match, rawGroup, sortOp);
        AggregationResults<Document> result =
                mongoTemplate.aggregate(pipeline, "llm_usage_records", Document.class);
        List<UsageBucketDto> out = new ArrayList<>();
        for (Document d : result.getMappedResults()) {
            out.add(mapper.apply(d));
        }
        out.sort(Comparator.comparing(
                b -> b.getBucketStart() == null ? Instant.EPOCH : b.getBucketStart()));
        return out;
    }

    private static long asLong(@Nullable Object raw) {
        if (raw instanceof Number n) return n.longValue();
        return 0L;
    }

    private static double asDouble(@Nullable Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static String asString(@Nullable Object raw, String fallback) {
        return raw == null ? fallback : raw.toString();
    }

    /**
     * Bucketing granularity for {@link #summary}. Matches Mongo
     * {@code $dateTrunc} unit names so we can pass it straight through.
     */
    private enum TimeBucket {
        DAY("day"),
        WEEK("week"),
        MONTH("month");

        private final String unit;

        TimeBucket(String unit) {
            this.unit = unit;
        }

        String unit() {
            return unit;
        }

        String label() {
            return unit;
        }

        static TimeBucket parse(@Nullable String raw) {
            if (raw == null) return DAY;
            String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (t) {
                case "week" -> WEEK;
                case "month" -> MONTH;
                default -> DAY;
            };
        }
    }
}
