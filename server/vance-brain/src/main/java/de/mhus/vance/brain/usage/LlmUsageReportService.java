package de.mhus.vance.brain.usage;

import de.mhus.vance.api.insights.UsageBucketDto;
import de.mhus.vance.api.insights.UsageReportDto;
import de.mhus.vance.shared.llmusage.LlmUsageDailyDocument;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
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
            String tenantId, Instant from, Instant to, String groupBy, @Nullable String projectId) {

        TimeBucket bucket = TimeBucket.parse(groupBy);
        MatchOperation match = matchTenantWindow(tenantId, from, to, projectId);

        // The stored `day` is a yyyy-MM-dd string (see LlmUsageDailyDocument
        // — a UTC calendar day, not an instant). Widening it to a week or
        // month happens as a $dateToString format, not as $dateTrunc: the
        // production database on the mini host is MongoDB 4.4, and $dateTrunc
        // is 5.0+. The key stays a canonical string per bucket
        // ('2026-09-14', '2026-09', '2026-37') — zero-padded, so the Mongo
        // sort and the Java parse agree on the order.
        Document groupKey = new Document().append("ts", bucket.keyExpression()).append("currency", "$currency");

        List<UsageBucketDto> rows = runPipeline(match, groupStage(groupKey), Sort.by(Sort.Order.asc("_id.ts")), doc -> {
            Document key = doc.get("_id", Document.class);
            return row(doc).bucketStart(bucket.startOf(key.getString("ts")))
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

    private UsageReportDto groupedByKey(String tenantId, Instant from, Instant to, String keyField, String label) {

        MatchOperation match = matchTenantWindow(tenantId, from, to, /*projectId*/ null);
        Document groupKey = new Document().append("key", "$" + keyField).append("currency", "$currency");

        List<UsageBucketDto> rows = runPipeline(
                match,
                groupStage(groupKey),
                // Cost first — it's the headline. Tokens break the tie so
                // unpriced models (all cost 0) still rank by how much they
                // actually burned instead of in arbitrary Mongo order.
                Sort.by(Sort.Order.desc("costTotalMicros"), Sort.Order.desc("tokensIn")),
                doc -> {
                    Document key = doc.get("_id", Document.class);
                    return row(doc).key(asString(key.get("key"), "?"))
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
                "tokensIn",
                "tokensOut",
                "cacheReadTokens",
                "implicitCacheReadTokens",
                "cacheWriteTokens",
                "images",
                "costInputMicros",
                "costOutputMicros",
                "costCacheReadMicros",
                "costCacheWriteMicros",
                "costTotalMicros",
                "calls",
                "callsFailed",
                "tokensInFailed",
                "tokensOutFailed",
                "unpricedCalls",
                "unpricedTokensIn",
                "unpricedTokensOut",
                "unmeasuredCalls")) {
            acc.append(f, new Document("$sum", "$" + f));
        }
        return new Document("$group", acc);
    }

    private static UsageBucketDto.UsageBucketDtoBuilder row(Document doc) {
        return UsageBucketDto.builder()
                .tokensIn(asLong(doc.get("tokensIn")))
                .tokensOut(asLong(doc.get("tokensOut")))
                .cacheReadTokens(asLong(doc.get("cacheReadTokens")))
                .implicitCacheReadTokens(asLong(doc.get("implicitCacheReadTokens")))
                .cacheWriteTokens(asLong(doc.get("cacheWriteTokens")))
                .images(asLong(doc.get("images")))
                // Stored as integer micro-units, reported as an amount. The
                // conversion happens once, here, at the edge — summing in
                // micros is what keeps the total exact.
                .costInput(LlmUsageService.fromMicros(asLong(doc.get("costInputMicros"))))
                .costOutput(LlmUsageService.fromMicros(asLong(doc.get("costOutputMicros"))))
                .costCacheRead(LlmUsageService.fromMicros(asLong(doc.get("costCacheReadMicros"))))
                .costCacheWrite(LlmUsageService.fromMicros(asLong(doc.get("costCacheWriteMicros"))))
                .costTotal(LlmUsageService.fromMicros(asLong(doc.get("costTotalMicros"))))
                .calls(asLong(doc.get("calls")))
                .callsFailed(asLong(doc.get("callsFailed")))
                .tokensInFailed(asLong(doc.get("tokensInFailed")))
                .tokensOutFailed(asLong(doc.get("tokensOutFailed")))
                .unpricedCalls(asLong(doc.get("unpricedCalls")))
                .unpricedTokensIn(asLong(doc.get("unpricedTokensIn")))
                .unpricedTokensOut(asLong(doc.get("unpricedTokensOut")))
                .unmeasuredCalls(asLong(doc.get("unmeasuredCalls")));
    }

    /**
     * Match on the {@code day} string rather than on a timestamp: the bucket
     * has no instant, only the UTC calendar day it belongs to. Both bounds
     * are converted the same way, so a window ending mid-day includes that
     * day's bucket in full — which is the only sensible reading of a
     * pre-aggregated day.
     */
    private MatchOperation matchTenantWindow(String tenantId, Instant from, Instant to, @Nullable String projectId) {
        String fromDay = LocalDate.ofInstant(from, ZoneOffset.UTC).toString();
        String toDay = LocalDate.ofInstant(to, ZoneOffset.UTC).toString();
        Criteria c =
                Criteria.where("tenantId").is(tenantId).and("day").gte(fromDay).lte(toDay);
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
        AggregationResults<Document> result = mongoTemplate.aggregate(pipeline, DAILY, Document.class);
        List<UsageBucketDto> out = new ArrayList<>();
        for (Document d : result.getMappedResults()) {
            out.add(mapper.apply(d));
        }
        out.sort(Comparator.comparing(b -> b.getBucketStart() == null ? Instant.EPOCH : b.getBucketStart()));
        return out;
    }

    private static long asLong(@Nullable Object raw) {
        if (raw instanceof Number n) return n.longValue();
        return 0L;
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
     * Bucketing granularity for {@link #summary}. Accepts the wire
     * names {@code day} / {@code week} / {@code month}; anything else
     * falls back to {@code day}.
     *
     * <p>Each bucket carries a canonical {@link #keyExpression()} Mongo
     * expression that projects the stored {@code day} string into the
     * bucket's own key string, and the matching {@link #startOf(String)}
     * parser that turns that key back into the row's {@code bucketStart}
     * instant. Both halves must agree — the tests pin the ISO-week edge
     * (a Friday in January can belong to the previous year's week 53).
     *
     * <p>Deliberately no {@code $dateTrunc}: it is MongoDB 5.0+, and the
     * production database is 4.4. Everything used here —
     * {@code $dateFromString}, {@code $dateToString} — predates 4.0.
     */
    private enum TimeBucket {
        /** Group by the stored day string itself — {@code 2026-09-14}. */
        DAY("day", null) {
            @Override
            Object keyExpression() {
                return "$day";
            }

            @Override
            Instant startOf(String key) {
                return LocalDate.parse(key).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
        },

        /** ISO 8601 week — {@code 2026-37}, Monday-based, week 1 holds Jan 4. */
        WEEK("week", "%G-%V") {
            @Override
            Instant startOf(String key) {
                int split = key.indexOf('-');
                int weekBasedYear = Integer.parseInt(key, 0, split, 10);
                int week = Integer.parseInt(key, split + 1, key.length(), 10);
                // Jan 4th is always in ISO week 1; snapping it to its Monday
                // gives week 1's start, every later week is whole weeks away.
                LocalDate mondayOfWeekOne = LocalDate.of(weekBasedYear, 1, 4).with(WeekFields.ISO.dayOfWeek(), 1);
                return mondayOfWeekOne
                        .plusWeeks(week - 1L)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            }
        },

        /** Calendar month — {@code 2026-09}. */
        MONTH("month", "%Y-%m") {
            @Override
            Instant startOf(String key) {
                int split = key.indexOf('-');
                return LocalDate.of(
                                Integer.parseInt(key, 0, split, 10),
                                Integer.parseInt(key, split + 1, key.length(), 10),
                                1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            }
        };

        /** {@code $dateToString} format of the bucket key, or {@code null}
         * when the stored day string is already the key. */
        private final @Nullable String format;

        /** Label on the wire ({@link #parse} input, DTO {@code bucketBy}). */
        private final String label;

        TimeBucket(String label, @Nullable String format) {
            this.label = label;
            this.format = format;
        }

        /**
         * Mongo expression producing the group key from the stored row —
         * a bare field path for {@link #DAY}, a {@code $dateToString}
         * expression otherwise.
         */
        Object keyExpression() {
            Document asDate = new Document(
                    "$dateFromString",
                    new Document()
                            .append("dateString", "$day")
                            .append("format", "%Y-%m-%d")
                            .append("timezone", "UTC"));
            return new Document(
                    "$dateToString", new Document().append("date", asDate).append("format", format));
        }

        /** The bucket a key string belongs to, as a UTC instant. */
        abstract Instant startOf(String key);

        String label() {
            return label;
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
