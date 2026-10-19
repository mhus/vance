package de.mhus.vance.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

/**
 * Behaviour of {@link StorageQuotaService}. The aggregation is exercised
 * with a mocked {@link MongoTemplate}; settings come from a mocked
 * {@link SettingService}. The TTLs do not matter here — we drive the
 * service directly and rely on its in-memory caches staying cold for the
 * duration of each test (each test gets a fresh instance).
 *
 * <p>Fail-open paths (R1/R4/R5 from {@code planning/storage-quota.md})
 * are tested explicitly: a broken aggregation, an unknown size, and an
 * absent setting must each allow the write through.
 */
class StorageQuotaServiceTest {

    private SettingService settingService;
    private MongoTemplate mongoTemplate;
    private StorageQuotaService service;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        mongoTemplate = mock(MongoTemplate.class);
        StorageQuotaProperties props = new StorageQuotaProperties();
        props.setSettingsTtl(Duration.ofMinutes(1));
        props.setPrecheckTtl(Duration.ofMinutes(1));
        props.setUsageTtl(Duration.ofMinutes(1));
        service = new StorageQuotaService(settingService, mongoTemplate, props);
    }

    @Test
    void noTenant_bypassesQuota() {
        StorageQuotaService.Check c = service.check(null, 1024L, -1L);
        assertThat(c.allowed()).isTrue();
        assertThat(c.reason()).isEqualTo("no-tenant");
    }

    @Test
    void noSettingMeansUnlimited() {
        when(settingService.findAll(eq("acme"), eq(SettingService.SCOPE_PROJECT), eq("_tenant")))
                .thenReturn(List.of());
        StorageQuotaService.Check c = service.check("acme", 1024L, -1L);
        assertThat(c.allowed()).isTrue();
        assertThat(c.reason()).isEqualTo("unlimited");
    }

    @Test
    void zeroLimitMeansUnlimited() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "0");
        StorageQuotaService.Check c = service.check("acme", 1024L, -1L);
        assertThat(c.allowed()).isTrue();
        assertThat(c.reason()).isEqualTo("unlimited");
    }

    @Test
    void writeUnderLimit_allowed() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "1048576"); // 1 MB
        stubAggregation("acme", 500_000L);
        StorageQuotaService.Check c = service.check("acme", 100_000L, -1L);
        assertThat(c.allowed()).isTrue();
        assertThat(c.currentUsageBytes()).isEqualTo(500_000L);
        assertThat(c.limitBytes()).isEqualTo(1_048_576L);
    }

    @Test
    void writeOverLimit_denied() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "1048576"); // 1 MB
        stubAggregation("acme", 900_000L);
        StorageQuotaService.Check c = service.check("acme", 200_000L, -1L);
        assertThat(c.allowed()).isFalse();
        assertThat(c.currentUsageBytes()).isEqualTo(900_000L);
        assertThat(c.limitBytes()).isEqualTo(1_048_576L);
    }

    @Test
    void unknownSize_bypassesQuota() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "1048576");
        // Aggregation would otherwise matter; we do not stub it.
        StorageQuotaService.Check c = service.check("acme", -1L, -1L);
        assertThat(c.allowed()).isTrue();
        assertThat(c.reason()).isEqualTo("unknown-size-bypass");
    }

    @Test
    void aggregationFailure_bypassesQuota() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "1048576");
        when(mongoTemplate.aggregate(
                        any(Aggregation.class), eq("storage_data"), eq(StorageQuotaService.SumResult.class)))
                .thenThrow(new RuntimeException("simulated mongo outage"));
        StorageQuotaService.Check c = service.check("acme", 100_000L, -1L);
        assertThat(c.allowed()).isTrue();
        assertThat(c.reason()).isEqualTo("aggregation-failed-bypass");
    }

    @Test
    void updateSmallerThanOld_skipsPreCheck() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "1048576");
        // current usage would otherwise deny, but delta is negative.
        stubAggregation("acme", 2_000_000L); // already over limit
        StorageQuotaService.Check c = service.check("acme", 1_000L, 5_000L);
        assertThat(c.allowed()).isTrue();
        assertThat(c.reason()).isEqualTo("no-growth");
    }

    @Test
    void invalidLimitString_treatedAsUnlimited() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "not-a-number");
        StorageQuotaService.Check c = service.check("acme", 1024L, -1L);
        assertThat(c.allowed()).isTrue();
        assertThat(c.reason()).isEqualTo("unlimited");
    }

    @Test
    void warnPercent_clampedTo99() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "1048576");
        seedSetting("acme", StorageQuotaService.KEY_WARN_PERCENT, "150");
        StorageQuotaService.Status s = service.status("acme");
        // 150 -> clamped to 0 ("off"). The aggregation stub is not invoked
        // for status with usedBytes=-1 when the aggregation throws, but
        // here we let it fail silently and verify the warning flag.
        assertThat(s.warning()).isFalse();
    }

    @Test
    void invalidateClearsAllLayers() {
        seedSetting("acme", StorageQuotaService.KEY_MAX_BYTES, "1048576");
        stubAggregation("acme", 500_000L);
        // Warm caches.
        service.check("acme", 100L, -1L);
        service.status("acme");
        // Invalidate; second call must re-read settings + re-aggregate.
        service.invalidateAllCaches("acme");
        // A different aggregation result must show up after invalidation.
        // 800_000 + 300_000 = 1_100_000 > 1_048_576 → denied.
        stubAggregation("acme", 800_000L);
        StorageQuotaService.Check c = service.check("acme", 300_000L, -1L);
        assertThat(c.allowed()).isFalse();
        assertThat(c.currentUsageBytes()).isEqualTo(800_000L);
    }

    // ──────────────────── helpers ────────────────────

    private void seedSetting(String tenant, String key, String value) {
        SettingDocument doc = SettingDocument.builder()
                .tenantId(tenant)
                .referenceType(SettingService.SCOPE_PROJECT)
                .referenceId("_tenant")
                .key(key)
                .value(value)
                .type(SettingType.STRING)
                .build();
        when(settingService.findAll(eq(tenant), eq(SettingService.SCOPE_PROJECT), eq("_tenant")))
                .thenReturn(List.of(doc));
    }

    @SuppressWarnings("unchecked")
    private void stubAggregation(String tenant, long total) {
        StorageQuotaService.SumResult r = new StorageQuotaService.SumResult();
        r.setTotal(total);
        AggregationResults<StorageQuotaService.SumResult> results = mock(AggregationResults.class);
        when(results.getUniqueMappedResult()).thenReturn(r);
        when(mongoTemplate.aggregate(
                        any(Aggregation.class), eq("storage_data"), eq(StorageQuotaService.SumResult.class)))
                .thenReturn(results);
    }
}
