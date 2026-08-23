package de.mhus.vance.brain.usage;

import de.mhus.vance.api.insights.UsageBucketDto;
import de.mhus.vance.api.insights.UsageReportDto;
import de.mhus.vance.shared.llmusage.LlmUsageDailyDocument;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Read-side of the usage ledger. Runs Mongo {@code $group} pipelines over
 * the pre-aggregated day buckets ({@code llm_usage_daily}) to produce:
 *
 * <ul>
 *   <li>{@link #summary} — time series by day / week / month, optional
 *       project filter;
 *   <li>{@link #byProject} — tenant-wide totals per project;
 *   <li>{@link #byModel} — per concrete model;
 *   <li>{@link #byCaller} / {@link #byRecipe} — per issuing subsystem resp.
 *       recipe.
 * </ul>
 *
 * <p><b>All five are projections of the same key</b>, which is why the day
 * bucket carries exactly the dimensions it does. Summing a few thousand
 * buckets is also what makes a multi-year window answerable at all —
 * the per-call rows it replaced expire after weeks and used to be the only
 * source.
 *
 * <p>Each bucket carries its currency, because rows can mix them
 * (Cortecs/EUR + Anthropic/USD on the same tenant). The aggregation groups
 * by {@code (bucket, currency)} so the report shows one series per currency
 * and never sums across them.
 *
 * <p>Failed attempts and unpriced volume ride along as their own counters
 * rather than being folded into the amount — see
 * {@link UsageBucketDto#getUnpricedCalls()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmUsageReportService {

    private static final String DAILY = "llm_usage_daily";
    private static final String DETAIL = "llm_usage_records";

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

        // The stored `day` is a yyyy-MM-dd string (see LlmUsageDailyDocument
        // — a UTC calendar day, not an instant), so it is turned back into a
        // date before $dateTrunc can widen it to a week or month.
        Document asDate = new Document("$dateFromString", new Document()
                .append("dateString", "$day")
                .append("format", "%Y-%m-%d")
                .append("timezone", "UTC"));
        Document trunc = new Document("$dateTrunc", new Document()
                .append("date", asDate)
                .append("unit", bucket.unit())
                .append("binSize", 1));
        Document groupKey = new Document()
                .append("ts", trunc)
                .append("currency", "$currency");

        List<UsageBucketDto> rows = runPipeline(
                match,
                groupStage(groupKey),
                Sort.by(Sort.Order.asc("_id.ts")),
                doc -> {
                    Document key = doc.get("_id", Document.class);
                    return row(doc)
                            .bucketStart(key.getDate("ts").toInstant())
                            .currency(asString(key.get("currency"), ""))
                            .build();
                });

        return UsageReportDto.builder()
                .from(from)
                .to(to)
                .bucketBy(bucket.label())
                .buckets(rows)
                .detailHorizon(detailHorizon(tenantId))
                .build();
    }

    public UsageReportDto byProject(String tenantId, Instant from, Instant to) {
        return groupedByKey(tenantId, from, to, "projectId", "project");
    }

    public UsageReportDto byModel(String tenantId, Instant from, Instant to) {
        return groupedByKey(tenantId, from, to, "providerModel", "model");
    }

    /**
     * Totals per issuing subsystem — answers "what is burning the budget",
     * which matters most for autonomous work that runs with nobody watching.
     *
     * <p>Named {@code caller} rather than {@code engine} because it has not
     * been an engine name for a long time: single-shot calls book as
     * {@code _light}, image generation as {@code _fenchurch}, memory upkeep
     * as {@code _compaction}.
     */
    public UsageReportDto byCaller(String tenantId, Instant from, Instant to) {
        return groupedByKey(tenantId, from, to, "caller", "caller");
    }

    /** Totals per recipe — the finer cut under {@link #byCaller}. */
    public UsageReportDto byRecipe(String tenantId, Instant from, Instant to) {
        return groupedByKey(tenantId, from, to, "recipeName", "recipe");
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

        List<UsageBucketDto> rows = runPipeline(
                match,
                groupStage(groupKey),
                // Cost first — it's the headline. Tokens break the tie so
                // unpriced models (all cost 0) still rank by how much they
                // actually burned instead of in arbitrary Mongo order.
                Sort.by(Sort.Order.desc("costTotal"), Sort.Order.desc("tokensIn")),
                doc -> {
                    Document key = doc.get("_id", Document.class);
                    return row(doc)
                            .key(asString(key.get("key"), "?"))
                            .currency(asString(key.get("currency"), ""))
                            .build();
                });

        return UsageReportDto.builder()
                .from(from)
                .to(to)
                .bucketBy(label)
                .buckets(rows)
                .detailHorizon(detailHorizon(tenantId))
                .build();
    }

    /** Sums every counter the bucket carries. One stage, five endpoints. */
    private static Document groupStage(Document groupKey) {
        Document acc = new Document("_id", groupKey);
        for (String f : List.of(
                "tokensIn", "tokensOut", "cacheReadTokens", "cacheWriteTokens", "images",
                "costInput", "costOutput", "costCacheRead", "costCacheWrite", "costTotal",
                "calls", "callsFailed", "tokensInFailed", "tokensOutFailed",
                "unpricedCalls", "unpricedTokensIn", "unpricedTokensOut")) {
            acc.append(f, new Document("$sum", "$" + f));
        }
        return new Document("$group", acc);
    }

    private static UsageBucketDto.UsageBucketDtoBuilder row(Document doc) {
        return UsageBucketDto.builder()
                .tokensIn(asLong(doc.get("tokensIn")))
                .tokensOut(asLong(doc.get("tokensOut")))
                .cacheReadTokens(asLong(doc.get("cacheReadTokens")))
                .cacheWriteTokens(asLong(doc.get("cacheWriteTokens")))
                .images(asLong(doc.get("images")))
                .costInput(asDouble(doc.get("costInput")))
                .costOutput(asDouble(doc.get("costOutput")))
                .costCacheRead(asDouble(doc.get("costCacheRead")))
                .costCacheWrite(asDouble(doc.get("costCacheWrite")))
                .costTotal(asDouble(doc.get("costTotal")))
                .calls(asLong(doc.get("calls")))
                .callsFailed(asLong(doc.get("callsFailed")))
                .tokensInFailed(asLong(doc.get("tokensInFailed")))
                .tokensOutFailed(asLong(doc.get("tokensOutFailed")))
                .unpricedCalls(asLong(doc.get("unpricedCalls")))
                .unpricedTokensIn(asLong(doc.get("unpricedTokensIn")))
                .unpricedTokensOut(asLong(doc.get("unpricedTokensOut")));
    }

    /**
     * Match on the {@code day} string rather than on a timestamp: the bucket
     * has no instant, only the UTC calendar day it belongs to. Both bounds
     * are converted the same way, so a window ending mid-day includes that
     * day's bucket in full — which is the only sensible reading of a
     * pre-aggregated day.
     */
    private MatchOperation matchTenantWindow(
            String tenantId, Instant from, Instant to, @Nullable String projectId) {
        String fromDay = LocalDate.ofInstant(from, ZoneOffset.UTC).toString();
        String toDay = LocalDate.ofInstant(to, ZoneOffset.UTC).toString();
        Criteria c = Criteria.where("tenantId").is(tenantId)
                .and("day").gte(fromDay).lte(toDay);
        if (projectId != null && !projectId.isBlank()) {
            c = c.and("projectId").is(projectId);
        }
        return Aggregation.match(c);
    }

    /**
     * Oldest per-call row this tenant still has. Read from the data, not
     * computed from the retention setting — a changed setting would make a
     * computed horizon lie about rows that are still there (or gone).
     * Index-covered by {@code tenant_createdAt_idx}.
     */
    private @Nullable Instant detailHorizon(String tenantId) {
        try {
            Query q = Query.query(Criteria.where("tenantId").is(tenantId))
                    .with(Sort.by(Sort.Order.asc("createdAt")))
                    .limit(1);
            q.fields().include("createdAt");
            Document oldest = mongoTemplate.findOne(q, Document.class, DETAIL);
            return oldest == null ? null : oldest.getDate("createdAt").toInstant();
        } catch (RuntimeException e) {
            log.debug("detailHorizon lookup failed for tenant='{}': {}", tenantId, e.toString());
            return null;
        }
    }

    private List<UsageBucketDto> runPipeline(
            MatchOperation match,
            Document group,
            Sort sort,
            java.util.function.Function<Document, UsageBucketDto> mapper) {

        AggregationOperation rawGroup = ctx -> group;
        Aggregation pipeline = Aggregation.newAggregation(match, rawGroup, Aggregation.sort(sort));
        AggregationResults<Document> result =
                mongoTemplate.aggregate(pipeline, DAILY, Document.class);
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
        if (raw == null) return fallback;
        // A key dimension is never null in the bucket; absent values are
        // stored as the sentinel. Translating it back keeps the sentinel out
        // of the UI.
        String s = raw.toString();
        return LlmUsageDailyDocument.NONE.equals(s) ? "" : s;
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
