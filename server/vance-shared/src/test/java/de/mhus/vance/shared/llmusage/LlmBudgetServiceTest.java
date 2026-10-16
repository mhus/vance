package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmBudgetService}: every level has to pass, each level counts its own
 * consumption, and a budget that cannot be read never blocks.
 */
class LlmBudgetServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 10, 1);

    private final SettingService settingService = mock(SettingService.class);
    private final LlmUsageService usageService = mock(LlmUsageService.class);
    private final LlmBudgetService service = new LlmBudgetService(
            settingService, usageService, Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void noSettingsByDefault() {
        when(settingService.findAll(eq(TENANT), anyString(), anyString())).thenReturn(List.of());
        when(usageService.costTotals(anyString(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void noBudget_allows_withoutReadingTheLedger() {
        LlmBudgetService.Verdict v = service.check(TENANT, PROJECT);

        assertThat(v.allowed()).isTrue();
        verify(usageService, never()).costTotals(anyString(), any(), any(), any());
    }

    @Test
    void projectDailyExhausted_refusesWithTheProjectWindow() {
        settings("research", Map.of(LlmBudget.KEY_DAILY, "1"));
        spent(PROJECT, TODAY, "USD", 1_000_000);

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
        spent(PROJECT, MONTH_START, "USD", 5_000_000);
        spent(null, MONTH_START, "USD", 10_000_000);

        LlmBudgetService.Verdict v = service.check(TENANT, PROJECT);

        assertThat(v.allowed()).isFalse();
        assertThat(v.denial().scope()).isEqualTo(BudgetScope.TENANT);
    }

    @Test
    void eachLevel_countsItsOwnConsumption() {
        settings("_tenant", Map.of(LlmBudget.KEY_DAILY, "10"));

        service.check(TENANT, PROJECT);

        // The tenant budget reads the whole tenant (projectId null), never the project slice.
        verify(usageService).costTotals(eq(TENANT), isNull(), eq(TODAY), eq(TODAY));
        verify(usageService, never()).costTotals(eq(TENANT), eq(PROJECT), any(), any());
    }

    @Test
    void otherCurrencies_doNotCount_butAreReported() {
        settings("research", Map.of(LlmBudget.KEY_DAILY, "1", LlmBudget.KEY_CURRENCY, "EUR"));
        when(usageService.costTotals(TENANT, PROJECT, TODAY, TODAY))
                .thenReturn(List.of(
                        new LlmUsageService.CostTotal("USD", 7_000_000, 0),
                        new LlmUsageService.CostTotal("EUR", 400_000, 0),
                        new LlmUsageService.CostTotal(LlmUsageDailyDocument.NONE, 0, 3)));

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
        spent(PROJECT, TODAY, "USD", 850_000);

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

        service.check(TENANT, PROJECT);

        verify(usageService).costTotals(TENANT, PROJECT, LocalDate.of(2026, 9, 1), TODAY);
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
    void limitsAreCached_consumptionIsNot() {
        settings("research", Map.of(LlmBudget.KEY_DAILY, "1"));

        service.check(TENANT, PROJECT);
        service.check(TENANT, PROJECT);

        verify(settingService, times(1)).findAll(TENANT, SettingService.SCOPE_PROJECT, PROJECT);
        verify(usageService, times(2)).costTotals(TENANT, PROJECT, TODAY, TODAY);
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

    private void spent(@Nullable String projectId, LocalDate from, String currency, long micros) {
        when(usageService.costTotals(TENANT, projectId, from, TODAY))
                .thenReturn(List.of(new LlmUsageService.CostTotal(currency, micros, 0)));
    }
}
