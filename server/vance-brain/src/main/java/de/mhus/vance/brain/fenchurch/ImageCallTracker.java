package de.mhus.vance.brain.fenchurch;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Persistent counter + quota gate for Fenchurch image-generation calls.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>Recording.</b> {@link #recordCall(ImageCallRecord)} writes
 *       one row per call to {@code image_call_records} and increments
 *       a Prometheus counter tagged by {@code outcome} and
 *       {@code model}. Successes <i>and</i> failures are recorded so
 *       quota reflects vendor-charged attempts, not just paid pixels.</li>
 *   <li><b>Quota check.</b> {@link #checkQuota} reads the relevant
 *       daily / monthly limits from the setting cascade
 *       ({@code think-process → _user_… → project → _tenant}, innermost
 *       wins) and compares them against the tenant-wide call count for
 *       the current day / month. Returns the granular failure reason
 *       on rejection so the caller's error mapping doesn't have to
 *       parse anything.</li>
 * </ol>
 *
 * <p><b>Reads are tenant-wide.</b> Setting limits per-user or per-project
 * works through the cascade — a more restrictive limit at an inner
 * layer wins — but the actual count comes from
 * {@code countByTenantIdAndAtGreaterThanEqual}. Translation: a user
 * who sets {@code ai.fenchurch.daily_images=10} caps the <i>whole
 * tenant</i> at 10 calls/day while that user is active. Per-account
 * count buckets land in v1.1.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageCallTracker {

    public static final String SETTING_DAILY = "ai.fenchurch.daily_images";
    public static final String SETTING_MONTHLY = "ai.fenchurch.monthly_images";

    private final ImageCallRecordRepository repository;
    private final SettingService settingService;
    private final MetricService metricService;

    /**
     * Persist one call record and increment the Prometheus counter.
     * Never throws — failing the audit-log must not also fail the
     * generation call. Errors are logged at WARN.
     */
    public void recordCall(ImageCallRecord record) {
        if (record == null) {
            return;
        }
        if (record.getAt() == null) {
            record.setAt(Instant.now());
        }
        try {
            repository.save(record);
        } catch (RuntimeException e) {
            log.warn("ImageCallTracker: failed to persist call record "
                    + "tenant='{}' model='{}': {}",
                    record.getTenantId(), record.getModelUsed(), e.toString());
        }
        String outcome = record.getOutcome() == null || record.getOutcome().isBlank()
                ? "unknown" : record.getOutcome();
        String alias = record.getAlias() == null || record.getAlias().isBlank()
                ? (record.getModelUsed() == null ? "unknown" : record.getModelUsed())
                : record.getAlias();
        metricService.counter("vance.fenchurch.calls",
                "outcome", outcome,
                "model", alias).increment();
    }

    /**
     * Resolve daily + monthly limits from the setting cascade, then
     * compare against the tenant-wide counts. Returns the verdict the
     * caller turns into a tool-result error or a green-light to call
     * the provider.
     *
     * <p>{@code userId} / {@code projectId} / {@code processId} may be
     * {@code null}; the corresponding cascade layer is then skipped.
     * A missing or non-positive limit means "unlimited" — the
     * cascading lookup intentionally returns the innermost positive
     * value, so a tenant-wide limit only kicks in when no inner layer
     * overrides it.
     */
    public Verdict checkQuota(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId) {
        long dailyLimit = readLimit(tenantId, userId, projectId, processId, SETTING_DAILY);
        long monthlyLimit = readLimit(tenantId, userId, projectId, processId, SETTING_MONTHLY);

        if (dailyLimit <= 0 && monthlyLimit <= 0) {
            return Verdict.OK;
        }

        if (dailyLimit > 0) {
            Instant startOfDay = LocalDate.now(ZoneOffset.UTC)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            long today = repository
                    .countByTenantIdAndAtGreaterThanEqual(tenantId, startOfDay);
            if (today >= dailyLimit) {
                return new Verdict(false, "daily",
                        "Daily Fenchurch image-generation limit reached ("
                                + today + " of " + dailyLimit + " calls today)");
            }
        }
        if (monthlyLimit > 0) {
            Instant startOfMonth = YearMonth.now(ZoneOffset.UTC)
                    .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            long thisMonth = repository
                    .countByTenantIdAndAtGreaterThanEqual(tenantId, startOfMonth);
            if (thisMonth >= monthlyLimit) {
                return new Verdict(false, "monthly",
                        "Monthly Fenchurch image-generation limit reached ("
                                + thisMonth + " of " + monthlyLimit + " calls this month)");
            }
        }
        return Verdict.OK;
    }

    private long readLimit(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId,
            String key) {
        String raw = readCascade(tenantId, userId, projectId, processId, key);
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("ImageCallTracker: non-numeric '{}' value '{}' — treating as unlimited",
                    key, raw);
            return 0;
        }
    }

    /**
     * Walk the cascade {@code think-process → _user_<userId> → projectId
     * → _tenant} and return the first scope that holds {@code key}.
     * Mirrors {@link SettingService#getStringValueUserProjectCascade}
     * but is inlined here so the cascade order is auditable without a
     * jump to the shared service.
     */
    private @Nullable String readCascade(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId,
            String key) {
        if (processId != null && !processId.isBlank()) {
            String v = settingService.getStringValue(
                    tenantId, SettingService.SCOPE_THINK_PROCESS, processId, key);
            if (v != null) return v;
        }
        if (userId != null && !userId.isBlank()) {
            String v = settingService.getStringValue(
                    tenantId, SettingService.SCOPE_PROJECT,
                    HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = settingService.getStringValue(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    /**
     * Result of a {@link #checkQuota} call. {@link #OK} means proceed;
     * any other value carries the failure reason and a user-facing
     * message.
     */
    public record Verdict(boolean allowed, @Nullable String reason, @Nullable String message) {

        public static final Verdict OK = new Verdict(true, null, null);
    }
}
