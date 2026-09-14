package de.mhus.vance.brain.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.mhus.vance.api.insights.UsageBucketDto;
import de.mhus.vance.api.insights.UsageReportDto;
import de.mhus.vance.shared.llmusage.LlmUsageDailyDocument;
import de.mhus.vance.shared.llmusage.UsageKind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Bucketing correctness of {@link LlmUsageReportService#summary} against a
 * real (embedded) MongoDB — not a mock, because the group key is a Mongo
 * expression and the whole point under test is that expression agreeing
 * with the Java parser that reads its result back.
 *
 * <p><b>Why this test exists:</b> the pipeline used {@code $dateTrunc} to
 * widen days into weeks and months — a MongoDB 5.0 operator. The production
 * database on the mini host is MongoDB 4.4, so every {@code /usage/summary}
 * call died with {@code InvalidPipelineOperator} and the Cost &amp; Usage
 * view showed nothing at all. The fix buckets through
 * {@code $dateToString} key strings (4.4-safe), and these tests pin the
 * week/month math — especially the ISO-week year boundary, where a Friday
 * in January belongs to the previous year's week 1.
 *
 * <p><b>Why embedded 7.0 and not 4.4:</b> MongoDB publishes no arm64 macOS
 * builds before 6.0, so a 4.4 test binary would only run under Rosetta. The
 * compatibility guarantee is by construction: the pipeline uses no operator
 * newer than {@code $dateFromString} / {@code $dateToString} (both pre-4.0)
 * — anything that passes here and avoids 5.0+ operators runs on 4.4.
 *
 * <p><b>Context:</b> the minimal boot brings only the embedded Mongo plus
 * the service under test — no Brain context, no LLM stack, no permission
 * provider. The write side that produces these documents has its own tests
 * in {@code vance-shared}; this test seeds day buckets directly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = LlmUsageReportServiceBucketingTest.TestConfig.class,
        properties = {
            "de.flapdoodle.mongodb.embedded.version=7.0.12",
            "spring.mongodb.uri=",
            "spring.mongodb.database=vance-usage-report-test",
            "spring.data.mongodb.auto-index-creation=false",
        })
class LlmUsageReportServiceBucketingTest {

    private static final String TENANT = "acme";
    private static final String OTHER_TENANT = "other";
    private static final String MODEL = "test-model";
    private static final String CALLER = "arthur";
    private static final Instant FROM = LocalDate.of(2025, 11, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final Instant TO = LocalDate.of(2026, 3, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(LlmUsageReportService.class)
    static class TestConfig {}

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private LlmUsageReportService service;

    @BeforeEach
    void clean() {
        mongoTemplate.dropCollection(LlmUsageDailyDocument.class);
    }

    @Test
    void summary_day_bucketsPerUtcDayAndCurrency() {
        seed("2026-01-02", "EUR", 100, 10, TENANT, MODEL);
        seed("2026-01-02", "USD", 50, 5, TENANT, MODEL);
        seed("2026-01-03", "EUR", 200, 20, TENANT, MODEL);
        // same day and currency as above, a different model — a second
        // document the day group must fold in (the writer merges into one
        // bucket per model; the $group has to sum what it finds)
        seed("2026-01-03", "EUR", 1, 1, TENANT, "other-model");
        seed("2026-01-04", "EUR", 400, 40, TENANT, MODEL);

        UsageReportDto report = service.summary(TENANT, FROM, TO, "day", null);

        // One row per (day, currency); EUR rows of the same day merge.
        // Order between two rows of the *same* day is unspecified (the
        // service sorts by bucketStart, and Mongo's group order between
        // currencies is not a contract) — hence anyOrder here, exact
        // order is pinned by the week/month tests with unique starts.
        assertThat(report.getBuckets())
                .extracting(UsageBucketDto::getBucketStart, UsageBucketDto::getCurrency, UsageBucketDto::getTokensIn)
                .containsExactlyInAnyOrder(
                        row("2026-01-02", "EUR", 100L),
                        row("2026-01-02", "USD", 50L),
                        row("2026-01-03", "EUR", 201L),
                        row("2026-01-04", "EUR", 400L));
    }

    @Test
    void summary_week_bucketsMondayStarts_acrossIsoYearBoundary() {
        // 2026-01-01 (Thursday) lies in ISO week 2026-W01, whose Monday is
        // 2025-12-29. The week bucket must start on that Monday, not on the
        // first day the window saw — and must not fall back into calendar
        // year 2025's numbering.
        seed("2026-01-01", "EUR", 10, 1);
        // Monday of the same week, one year earlier on the calendar:
        seed("2025-12-29", "EUR", 20, 2);
        seed("2025-12-31", "EUR", 30, 3);
        // A different week:
        seed("2026-01-05", "EUR", 40, 4);

        UsageReportDto report = service.summary(TENANT, FROM, TO, "week", null);

        assertThat(report.getBuckets())
                .extracting(UsageBucketDto::getBucketStart, UsageBucketDto::getTokensIn, UsageBucketDto::getCalls)
                .containsExactly(row("2025-12-29", 60L, 3L), row("2026-01-05", 40L, 1L));
    }

    @Test
    void summary_month_bucketsCalendarMonths() {
        seed("2025-12-01", "EUR", 10, 1);
        seed("2025-12-31", "EUR", 20, 2);
        seed("2026-01-01", "EUR", 30, 3);
        seed("2026-01-31", "EUR", 40, 4);

        UsageReportDto report = service.summary(TENANT, FROM, TO, "month", null);

        assertThat(report.getBuckets())
                .extracting(UsageBucketDto::getBucketStart, UsageBucketDto::getTokensIn)
                .containsExactly(row("2025-12-01", 30L), row("2026-01-01", 70L));
    }

    @Test
    void summary_tenantAndProjectWindow_stayScoped() {
        seed("2026-01-02", "EUR", 100, 10);
        seed("2026-01-02", "EUR", 5, 5, OTHER_TENANT, MODEL);
        // day outside the window — pre-aggregated days are matched as
        // calendar strings, not instants
        seed("2026-09-14", "EUR", 999, 999);

        UsageReportDto report = service.summary(TENANT, FROM, TO, "day", null);

        assertThat(report.getBuckets()).hasSize(1);
        assertThat(report.getBuckets().getFirst().getTokensIn()).isEqualTo(100L);
    }

    @Test
    void summary_unknownGroupBy_fallsBackToDay() {
        seed("2026-01-02", "EUR", 100, 10);

        UsageReportDto report = service.summary(TENANT, FROM, TO, "quarter", null);

        assertThat(report.getBucketBy()).isEqualTo("day");
        assertThat(report.getBuckets()).hasSize(1);
    }

    @Test
    void byModel_stillGroupsAndRanks() {
        seed("2026-01-02", "EUR", 100, 10);
        seed("2026-01-03", "EUR", 50, 5);

        UsageReportDto report = service.byModel(TENANT, FROM, TO);

        // The $dateTrunc fix must not have touched the key-based cuts.
        assertThat(report.getBuckets()).hasSize(1);
        assertThat(report.getBuckets().getFirst().getKey()).isEqualTo(MODEL);
        assertThat(report.getBuckets().getFirst().getCalls()).isEqualTo(2L);
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    /** {@code (bucketStart, …)} tuple: day string → UTC-midnight instant. */
    private static Tuple row(String day, Object... rest) {
        Object[] out = new Object[rest.length + 1];
        out[0] = LocalDate.parse(day).atStartOfDay().toInstant(ZoneOffset.UTC);
        System.arraycopy(rest, 0, out, 1, rest.length);
        return tuple(out);
    }

    private void seed(String day, String currency, long tokensIn, long tokensOut) {
        seed(day, currency, tokensIn, tokensOut, TENANT, MODEL);
    }

    private void seed(String day, String currency, long tokensIn, long tokensOut, String tenantId, String model) {
        mongoTemplate.insert(LlmUsageDailyDocument.builder()
                .bucketId(LlmUsageDailyDocument.bucketId(
                        tenantId,
                        day,
                        LlmUsageDailyDocument.NONE,
                        CALLER,
                        LlmUsageDailyDocument.NONE,
                        model,
                        currency,
                        UsageKind.CHAT))
                .tenantId(tenantId)
                .day(day)
                .projectId(LlmUsageDailyDocument.NONE)
                .caller(CALLER)
                .recipeName(LlmUsageDailyDocument.NONE)
                .providerModel(model)
                .currency(currency)
                .kind(UsageKind.CHAT)
                .calls(1)
                .tokensIn(tokensIn)
                .tokensOut(tokensOut)
                .costTotalMicros(1_000)
                .firstAt(LlmUsageDailyDocument.dayStart(day))
                .lastAt(LlmUsageDailyDocument.dayStart(day))
                .build());
    }
}
