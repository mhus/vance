package de.mhus.vance.brain.hotblack;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.hotblack.AudioCallRecord;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Persistent counter + quota gate for Hotblack audio calls.
 *
 * <p>Two responsibilities, same shape as {@code ImageCallTracker}:
 * {@link #recordCall} writes one row per call (success <i>and</i>
 * failure) and bumps a Prometheus counter; {@link #reserve} closes the
 * check-then-call-then-record TOCTOU by inserting a pending row before
 * the (long) provider call and rolling it back when the limit is
 * exceeded.
 *
 * <p><b>Limits count calls, not units.</b> The per-call reserve
 * mechanism cannot see how many characters or seconds a call will
 * produce before it runs, so the gate is per call and per modality
 * ({@code daily-tts-calls} etc.). The unit counts (characters, audio
 * seconds) are recorded on every row in {@code inputUnits} for
 * analytics and a future unit-based limit — deliberately not guessed
 * into the gate.
 *
 * <p><b>Reads are tenant-wide.</b> Setting a limit at an inner layer
 * works through the cascade (more restrictive wins), but the count
 * comes from the tenant-wide query — same known trade-off as
 * Fenchurch v1 (per-account buckets are a shared refactor story).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudioCallTracker {

    public static final String SETTING_DAILY_TTS = "ai.hotblack.daily-tts-calls";
    public static final String SETTING_MONTHLY_TTS = "ai.hotblack.monthly-tts-calls";
    public static final String SETTING_DAILY_STT = "ai.hotblack.daily-stt-calls";
    public static final String SETTING_MONTHLY_STT = "ai.hotblack.monthly-stt-calls";
    public static final String SETTING_DAILY_MUSIC = "ai.hotblack.daily-music-calls";
    public static final String SETTING_MONTHLY_MUSIC = "ai.hotblack.monthly-music-calls";

    /** Outcome marker for a reserved-but-not-yet-finalized call row. */
    public static final String OUTCOME_PENDING = "pending";

    /**
     * Outcome for a reserve row whose call never finalized it (crash /
     * interrupt) — swept by {@link #sweepAbandonedReserves}. Kept in the fixed
     * outcome vocabulary of {@link AudioCallRecord}: an attempt happened and
     * counts against the quota, it just never reported.
     */
    public static final String OUTCOME_CANCELLED = "cancelled";

    /**
     * How long a reserve row may stay {@code pending} before it counts as
     * abandoned. Comfortably above every legal call — the largest shipped
     * per-call timeout is the 3600 s faster-whisper STT.
     */
    static final Duration PENDING_RESERVE_MAX_AGE = Duration.ofHours(24);

    private final AudioCallRecordRepository repository;
    private final SettingService settingService;
    private final MetricService metricService;

    /**
     * Persist one call record and increment the Prometheus counter.
     * Never throws — failing the audit-log must not also fail the call.
     * A record carrying an id finalizes the matching reserve row in
     * place (one row, stable count).
     */
    public void recordCall(AudioCallRecord record) {
        if (record == null) {
            return;
        }
        if (record.getAt() == null) {
            record.setAt(Instant.now());
        }
        try {
            repository.save(record);
        } catch (RuntimeException e) {
            log.warn(
                    "AudioCallTracker: failed to persist call record tenant='{}' model='{}': {}",
                    record.getTenantId(),
                    record.getModelUsed(),
                    e.toString());
        }
        String outcome = record.getOutcome() == null || record.getOutcome().isBlank() ? "unknown" : record.getOutcome();
        String alias = record.getAlias() == null || record.getAlias().isBlank()
                ? (record.getModelUsed() == null ? "unknown" : record.getModelUsed())
                : record.getAlias();
        metricService
                .counter(
                        "vance.hotblack.calls",
                        "outcome",
                        outcome,
                        "modality",
                        record.getModality() == null ? "unknown" : record.getModality(),
                        "model",
                        alias)
                .increment();
    }

    /**
     * Finalize reserve rows whose call never reached {@link #recordCall} —
     * a crashed pod, a killed thread, anything that skipped the finalize.
     * Left at {@code pending} they would count against the quota for the rest
     * of the window (the monthly window up to a month). Flipped to
     * {@code cancelled}, not deleted: an attempt was made, and quota math
     * counts attempts. A late finalize of the same row overwrites the outcome
     * in place, so a sweep that fires early on a call exceeding
     * {@link #PENDING_RESERVE_MAX_AGE} self-corrects.
     */
    @Scheduled(
            fixedDelayString = "${vance.hotblack.pending-sweep.interval:PT1H}",
            initialDelayString = "${vance.hotblack.pending-sweep.interval:PT1H}")
    public void sweepAbandonedReserves() {
        try {
            List<AudioCallRecord> stale = repository.findByOutcomeAndAtBefore(
                    OUTCOME_PENDING, Instant.now().minus(PENDING_RESERVE_MAX_AGE));
            for (AudioCallRecord record : stale) {
                record.setOutcome(OUTCOME_CANCELLED);
                repository.save(record);
            }
            if (!stale.isEmpty()) {
                log.warn(
                        "AudioCallTracker: finalized {} abandoned pending reserve(s) as '{}'",
                        stale.size(),
                        OUTCOME_CANCELLED);
            }
        } catch (RuntimeException e) {
            log.warn("AudioCallTracker: abandoned-reserve sweep failed: {}", e.toString());
        }
    }

    /**
     * Atomically reserve a quota slot before the provider call. The
     * reserve is a pending row counted against the limits; on success
     * the caller finalizes by writing the real record with
     * {@link Granted#reserveId()} as its id.
     */
    public Reservation reserve(
            String modality,
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId) {
        String dailyKey = dailyKey(modality);
        String monthlyKey = monthlyKey(modality);
        long dailyLimit = readLimit(tenantId, userId, projectId, processId, dailyKey);
        long monthlyLimit = readLimit(tenantId, userId, projectId, processId, monthlyKey);

        AudioCallRecord reserve = AudioCallRecord.builder()
                .tenantId(tenantId)
                .accountId(userId)
                .projectId(projectId)
                .modality(modality)
                .outcome(OUTCOME_PENDING)
                .at(Instant.now())
                .build();
        repository.save(reserve);

        if (dailyLimit > 0) {
            Instant startOfDay =
                    LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
            long today = repository.countByTenantIdAndModalityAndAtGreaterThanEqual(tenantId, modality, startOfDay);
            if (today > dailyLimit) {
                repository.delete(reserve);
                return new Denied(new Verdict(
                        false,
                        "daily",
                        "Daily audio limit reached for " + modality + " (" + (today - 1) + " of " + dailyLimit
                                + " calls today)"));
            }
        }
        if (monthlyLimit > 0) {
            Instant startOfMonth = YearMonth.now(ZoneOffset.UTC)
                    .atDay(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
            long thisMonth =
                    repository.countByTenantIdAndModalityAndAtGreaterThanEqual(tenantId, modality, startOfMonth);
            if (thisMonth > monthlyLimit) {
                repository.delete(reserve);
                return new Denied(new Verdict(
                        false,
                        "monthly",
                        "Monthly audio limit reached for " + modality + " (" + (thisMonth - 1) + " of " + monthlyLimit
                                + " calls this month)"));
            }
        }
        return new Granted(reserve.getId());
    }

    /** Outcome of {@link #reserve}: a granted slot or a quota denial. */
    public sealed interface Reservation permits Granted, Denied {}

    /** The reserve row was inserted and survived the limit check. */
    public record Granted(String reserveId) implements Reservation {}

    /** The limit was hit; the reserve row was rolled back. */
    public record Denied(Verdict verdict) implements Reservation {}

    private static String dailyKey(String modality) {
        return switch (modality) {
            case "tts" -> SETTING_DAILY_TTS;
            case "stt" -> SETTING_DAILY_STT;
            default -> SETTING_DAILY_MUSIC;
        };
    }

    private static String monthlyKey(String modality) {
        return switch (modality) {
            case "tts" -> SETTING_MONTHLY_TTS;
            case "stt" -> SETTING_MONTHLY_STT;
            default -> SETTING_MONTHLY_MUSIC;
        };
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
            log.warn("AudioCallTracker: non-numeric '{}' value '{}' — treating as unlimited", key, raw);
            return 0;
        }
    }

    /**
     * Walk the cascade {@code think-process → _user_<userId> → projectId
     * → _tenant} and return the first scope that holds {@code key} —
     * same auditable inline cascade as {@code ImageCallTracker}.
     */
    private @Nullable String readCascade(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId,
            String key) {
        if (processId != null && !processId.isBlank()) {
            String v = settingService.getStringValue(tenantId, SettingService.SCOPE_THINK_PROCESS, processId, key);
            if (v != null) return v;
        }
        if (userId != null && !userId.isBlank()) {
            String v = settingService.getStringValue(
                    tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId, key);
            if (v != null) return v;
        }
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = settingService.getStringValue(tenantId, SettingService.SCOPE_PROJECT, projectId, key);
            if (v != null) return v;
        }
        return settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
    }

    /**
     * Result of a quota decision. {@link #OK} means proceed; any other
     * value carries the failure reason and a user-facing message.
     */
    public record Verdict(
            boolean allowed,
            @Nullable String reason,
            @Nullable String message) {

        public static final Verdict OK = new Verdict(true, null, null);
    }
}
