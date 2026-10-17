package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link LlmBudgetService}: every level has to pass, each level counts its own
 * consumption, one ledger read per level, and a budget that cannot be read
 * never blocks.
 *
 * <p>The ledger is a small in-memory fake behind the mocked
 * {@link LlmUsageService#costTotals(String, String, List)}: bookings per
 * project, day and currency, summed per requested range the way the
 * aggregation does it.
 */
class LlmBudgetServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 10, 1);

    private final SettingService settingService = mock(SettingService.class);
    private final LlmUsageService usageService = mock(LlmUsageService.class);
    private final LlmBudgetService service = new LlmBudgetService(
            settingService, usageService, Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC));

    private final List<Booking> ledger = new ArrayList<>();

    @BeforeEach
    void noSettingsByDefault_andAFakeLedger() {
        when(settingService.findAll(eq(TENANT), anyString(), anyString())).thenReturn(List.of());
        when(usageService.costTotals(anyString(), any(), anyList())).thenAnswer(inv -> {
            @Nullable String projectId = inv.getArgument(1);
            List<LlmUsageService.DayRange> ranges = inv.getArgument(2);
            return sum(projectId, ranges);
        });
    }

    @Test
    void noBudget_allows_withoutReadingTheLedger() {
        LlmBudgetService.Verdict v = service.check(TENANT, PROJECT);

        assertThat(v.allowed()).isTrue();
        verify(usageService, never()).costTotals(anyString(), any(), anyList());
    }

    @Test
    void projectDailyExhausted_refusesWithTheProjectWindow() {
        settings("research", Map.of(LlmBudget.KEY_DAILY, "1"));
        book(PROJECT, TODAY, "USD", 1_000_000);

        LlmBudgetService.Verdict v = service.check(TENANT, PROJECT);

        assertThat(v.allowed()).isFalse();
        assertThat(v.denial().scope()).isEqualTo(BudgetScope.PROJECT);
        assertThat(v.denial().window()).isEqualTo(BudgetWindow.DAILY);
        assertThat(v.denial().usedMicros()).isEqualTo(1_000_000);
    }

    @Test
    void tenantLimit_holds_evenWhenTheProjectSetsAHigherOne() {
        settings("research", Map.of(LlmBudget.KEY_MONTHLY, "1000"));
        settings("_tenant", Map.of(LlmBudget.KEY_MONTHLY, "10"));
        book(PROJECT, MONTH_START, "USD", 5_000_000);
        book("other", MONTH_START.plusDays(3), "USD", 5_000_000);

        LlmBudgetService.Verdict v = service.check(TENANT, PROJECT);

        assertThat(v.allowed()).isFalse();
        assertThat(v.denial().scope()).isEqualTo(BudgetScope.TENANT);
        assertThat(v.denial().usedMicros()).isEqualTo(10_000_000);
    }

    @Test
    void eachLevel_countsItsOwnConsumption() {
        settings("_tenant", Map.of(LlmBudget.KEY_DAILY, "10"));

        service.check(TENANT, PROJECT);

        // The tenant budget reads the whole tenant (projectId null), never the project slice.
        verify(usageService).costTotals(eq(TENANT), isNull(), anyList());
        verify(usageService, never()).costTotals(eq(TENANT), eq(PROJECT), anyList());
    }

    @Test
    void allWindowsOfALevel_shareOneLedgerRead() {
        settings(
                "research",
                Map.of(
                        LlmBudget.KEY_DAILY, "100",
                        LlmBudget.KEY_MONTHLY, "100",
                        LlmBudget.KEY_TOTAL, "100",
                        LlmBudget.KEY_TOTAL_SINCE, "2026-09-01"));

        service.check(TENANT, PROJECT);

        ArgumentCaptor<List<LlmUsageService.DayRange>> ranges = rangesCaptor();
        verify(usageService, times(1)).costTotals(eq(TENANT), eq(PROJECT), ranges.capture());
        // Today fresh, plus the closed days of each window that started earlier.
        assertThat(ranges.getValue())
                .containsExactlyInAnyOrder(
                        new LlmUsageService.DayRange(TODAY, TODAY),
                        new LlmUsageService.DayRange(LocalDate.of(2026, 9, 1), YESTERDAY),
                        new LlmUsageService.DayRange(MONTH_START, YESTERDAY));
    }

    @Test
    void windows_addTodayToTheirClosedDays() {
        settings(
                "research",
                Map.of(
                        LlmBudget.KEY_DAILY, "100",
                        LlmBudget.KEY_MONTHLY, "100",
                        LlmBudget.KEY_TOTAL, "100",
                        LlmBudget.KEY_TOTAL_SINCE, "2026-09-01"));
        book(PROJECT, LocalDate.of(2026, 9, 15), "USD", 4_000_000);
        book(PROJECT, MONTH_START, "USD", 2_000_000);
        book(PROJECT, YESTERDAY, "USD", 1_000_000);
        book(PROJECT, TODAY, "USD", 500_000);
        book("other", TODAY, "USD", 9_000_000);

        Map<BudgetWindow, Long> used = new LinkedHashMap<>();
        service.status(TENANT, PROJECT).get(0).windows().forEach(w -> used.put(w.window(), w.usedMicros()));

        assertThat(used)
                .containsEntry(BudgetWindow.DAILY, 500_000L)
                .containsEntry(BudgetWindow.MONTHLY, 3_500_000L)
                .containsEntry(BudgetWindow.TOTAL, 7_500_000L);
    }

    @Test
    void closedDays_areCached_todayIsNot() {
        settings("research", Map.of(LlmBudget.KEY_MONTHLY, "10"));
        book(PROJECT, MONTH_START, "USD", 9_000_000);

        assertThat(service.check(TENANT, PROJECT).allowed()).isTrue();
        // A booking today is seen at once — a burst must not outrun the limit.
        book(PROJECT, TODAY, "USD", 1_000_000);
        // A late booking into a closed day is not — until the cache expires.
        book(PROJECT, YESTERDAY, "USD", 5_000_000);
        LlmBudgetService.Verdict second = service.check(TENANT, PROJECT);

        assertThat(second.allowed()).isFalse();
        assertThat(second.denial().usedMicros()).isEqualTo(10_000_000);
        ArgumentCaptor<List<LlmUsageService.DayRange>> ranges = rangesCaptor();
        verify(usageService, times(2)).costTotals(eq(TENANT), eq(PROJECT), ranges.capture());
        assertThat(ranges.getAllValues().get(0))
                .containsExactlyInAnyOrder(
                        new LlmUsageService.DayRange(TODAY, TODAY),
                        new LlmUsageService.DayRange(MONTH_START, YESTERDAY));
        assertThat(ranges.getAllValues().get(1)).containsExactly(new LlmUsageService.DayRange(TODAY, TODAY));

        service.invalidate();
        assertThat(service.status(TENANT, PROJECT).get(0).windows().get(0).usedMicros())
                .isEqualTo(15_000_000);
    }

    @Test
    void sameStartDay_isReadOnce() {
        settings(
                "research",
                Map.of(
                        LlmBudget.KEY_MONTHLY, "100",
                        LlmBudget.KEY_TOTAL, "100",
                        LlmBudget.KEY_TOTAL_SINCE, "2026-10-01"));

        service.check(TENANT, PROJECT);

        ArgumentCaptor<List<LlmUsageService.DayRange>> ranges = rangesCaptor();
        verify(usageService).costTotals(eq(TENANT), eq(PROJECT), ranges.capture());
        assertThat(ranges.getValue())
                .containsExactlyInAnyOrder(
                        new LlmUsageService.DayRange(TODAY, TODAY),
                        new LlmUsageService.DayRange(MONTH_START, YESTERDAY));
    }

    @Test
    void otherCurrencies_doNotCount_butAreReported() {
        settings("research", Map.of(LlmBudget.KEY_DAILY, "1", LlmBudget.KEY_CURRENCY, "EUR"));
        book(PROJECT, TODAY, "USD", 7_000_000);
        book(PROJECT, TODAY, "EUR", 400_000);
        ledger.add(new Booking(PROJECT, TODAY, LlmUsageDailyDocument.NONE, 0, 3));

        LlmBudgetService.WindowStatus w =
                service.status(TENANT, PROJECT).get(0).windows().get(0);

        assertThat(service.check(TENANT, PROJECT).allowed()).isTrue();
        assertThat(w.usedMicros()).isEqualTo(400_000);
        assertThat(w.uncountedMicros()).containsExactly(Map.entry("USD", 7_000_000L));
        assertThat(w.unpricedCalls()).isEqualTo(3);
    }

    @Test
    void pastTheWarnThreshold_warns_butAllows() {
        settings("research", Map.of(LlmBudget.KEY_DAILY, "1"));
        book(PROJECT, TODAY, "USD", 850_000);

        LlmBudgetService.Verdict v = service.check(TENANT, PROJECT);

        assertThat(v.allowed()).isTrue();
        assertThat(v.warnings()).singleElement().satisfies(w -> {
            assertThat(w.window()).isEqualTo(BudgetWindow.DAILY);
            assertThat(w.percent()).isEqualTo(85.0);
        });
    }

    @Test
    void totalBudget_countsFromItsStartDate() {
        settings("research", Map.of(LlmBudget.KEY_TOTAL, "500", LlmBudget.KEY_TOTAL_SINCE, "2026-09-01"));
        book(PROJECT, LocalDate.of(2026, 8, 31), "USD", 100_000_000);
        book(PROJECT, LocalDate.of(2026, 9, 1), "USD", 1_000_000);

        LlmBudgetService.WindowStatus w =
                service.status(TENANT, PROJECT).get(0).windows().get(0);

        assertThat(w.window()).isEqualTo(BudgetWindow.TOTAL);
        assertThat(w.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(w.usedMicros()).isEqualTo(1_000_000);
    }

    @Test
    void tenantContext_hasOnlyTheTenantLevel() {
        assertThat(service.status(TENANT, "_tenant"))
                .extracting(LlmBudgetService.ScopeStatus::scope)
                .containsExactly(BudgetScope.TENANT);
        assertThat(service.status(TENANT, null))
                .extracting(LlmBudgetService.ScopeStatus::scope)
                .containsExactly(BudgetScope.TENANT);
        assertThat(service.status(TENANT, PROJECT))
                .extracting(LlmBudgetService.ScopeStatus::scope)
                .containsExactly(BudgetScope.PROJECT, BudgetScope.TENANT);
    }

    @Test
    void limitsAreCached() {
        settings("research", Map.of(LlmBudget.KEY_DAILY, "1"));

        service.check(TENANT, PROJECT);
        service.check(TENANT, PROJECT);

        verify(settingService, times(1)).findAll(TENANT, SettingService.SCOPE_PROJECT, PROJECT);
        verify(usageService, times(2)).costTotals(eq(TENANT), eq(PROJECT), anyList());
    }

    @Test
    void encryptedSettings_areIgnored() {
        SettingDocument secret = setting(LlmBudget.KEY_DAILY, "1");
        secret.setType(SettingType.PASSWORD);
        when(settingService.findAll(TENANT, SettingService.SCOPE_PROJECT, PROJECT))
                .thenReturn(List.of(secret));

        assertThat(service.status(TENANT, PROJECT).get(0).budget().hasLimit()).isFalse();
    }

    @Test
    void unreadableBudget_allows() {
        when(settingService.findAll(eq(TENANT), anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(service.check(TENANT, PROJECT).allowed()).isTrue();
    }

    @Test
    void unreadableLedger_allows() {
        settings("research", Map.of(LlmBudget.KEY_DAILY, "1"));
        when(usageService.costTotals(anyString(), any(), anyList())).thenThrow(new IllegalStateException("db down"));

        assertThat(service.check(TENANT, PROJECT).allowed()).isTrue();
    }

    @Test
    void ungatedCallers_areCompactionAndTriage() {
        assertThat(LlmBudgetService.isUngated(LlmUsageService.CALLER_COMPACTION))
                .isTrue();
        assertThat(LlmBudgetService.isUngated(LlmUsageService.CALLER_TRIAGE)).isTrue();
        assertThat(LlmBudgetService.isUngated("arthur")).isFalse();
    }

    // ──────────────────── helpers ────────────────────

    private void settings(String scopeId, Map<String, String> values) {
        List<SettingDocument> docs = new ArrayList<>();
        values.forEach((k, v) -> docs.add(setting(k, v)));
        // An unrelated setting in the same scope must not leak into the budget.
        docs.add(setting("ai.default.model", "kimi-k3"));
        when(settingService.findAll(TENANT, SettingService.SCOPE_PROJECT, scopeId))
                .thenReturn(docs);
    }

    private static SettingDocument setting(String key, String value) {
        SettingDocument doc = new SettingDocument();
        doc.setKey(key);
        doc.setValue(value);
        doc.setType(SettingType.STRING);
        return doc;
    }

    private void book(String projectId, LocalDate day, String currency, long micros) {
        ledger.add(new Booking(projectId, day, currency, micros, 0));
    }

    /** What the aggregation answers: per range, per currency, only non-empty sums. */
    private Map<LlmUsageService.DayRange, List<LlmUsageService.CostTotal>> sum(
            @Nullable String projectId, List<LlmUsageService.DayRange> ranges) {
        Map<LlmUsageService.DayRange, List<LlmUsageService.CostTotal>> out = new LinkedHashMap<>();
        for (LlmUsageService.DayRange range : ranges) {
            Map<String, long[]> perCurrency = new LinkedHashMap<>();
            for (Booking b : ledger) {
                boolean inSlice = projectId == null || Objects.equals(projectId, b.projectId());
                boolean inRange = !b.day().isBefore(range.from()) && !b.day().isAfter(range.to());
                if (inSlice && inRange) {
                    long[] acc = perCurrency.computeIfAbsent(b.currency(), c -> new long[2]);
                    acc[0] += b.micros();
                    acc[1] += b.unpriced();
                }
            }
            List<LlmUsageService.CostTotal> totals = new ArrayList<>();
            perCurrency.forEach((c, acc) -> totals.add(new LlmUsageService.CostTotal(c, acc[0], acc[1])));
            out.put(range, totals);
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<LlmUsageService.DayRange>> rangesCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private record Booking(String projectId, LocalDate day, String currency, long micros, long unpriced) {}
}
