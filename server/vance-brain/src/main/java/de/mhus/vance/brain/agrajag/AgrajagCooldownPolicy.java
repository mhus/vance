package de.mhus.vance.brain.agrajag;

import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for the upper bound on Agrajag-set cooldowns
 * and {@code expectedRecoveryAt} values. Resolved via the standard
 * setting cascade {@code think-process → project → _tenant} under the
 * key {@code agrajag.cooldown.max}, with a hard fallback of 24h.
 *
 * <p>Manual cooldowns set via {@code tool_health_set_cooldown}
 * (user-driven) are deliberately NOT capped here — the user knows what
 * they want.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgrajagCooldownPolicy {

    /** Setting key, ISO-8601 Duration string (e.g. {@code PT1H}, {@code PT24H}). */
    public static final String SETTING_KEY = "agrajag.cooldown.max";

    /** Fall-back when no scope in the cascade defines the setting. */
    public static final Duration DEFAULT_MAX_COOLDOWN = Duration.ofHours(24);

    /**
     * Lower bound for the {@link #SETTING_KEY} value. Settings below
     * this are clamped up — a max-cooldown shorter than one hour would
     * make the entire cooldown mechanism useless (every transient
     * blip re-floods the tool).
     */
    public static final Duration MIN_MAX_COOLDOWN = Duration.ofHours(1);

    private final SettingService settingService;

    /**
     * Effective maximum cooldown for the given scope. Reads the
     * cascade once per call — cheap (single document lookup per layer)
     * and avoids the staleness of caching.
     */
    public Duration getMax(@Nullable String tenantId,
                           @Nullable String projectId,
                           @Nullable String processId) {
        if (tenantId == null || tenantId.isBlank()) return DEFAULT_MAX_COOLDOWN;
        String raw = settingService.getStringValueCascade(
                tenantId, projectId, processId, SETTING_KEY);
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_COOLDOWN;
        try {
            Duration d = Duration.parse(raw.trim());
            if (d.isNegative() || d.isZero()) {
                log.warn("AgrajagCooldownPolicy: {} resolved to '{}' which is "
                                + "non-positive — falling back to default {}",
                        SETTING_KEY, raw, DEFAULT_MAX_COOLDOWN);
                return DEFAULT_MAX_COOLDOWN;
            }
            if (d.compareTo(MIN_MAX_COOLDOWN) < 0) {
                log.warn("AgrajagCooldownPolicy: {} resolved to '{}' which is "
                                + "below the minimum {} — clamping up",
                        SETTING_KEY, raw, MIN_MAX_COOLDOWN);
                return MIN_MAX_COOLDOWN;
            }
            return d;
        } catch (RuntimeException e) {
            log.warn("AgrajagCooldownPolicy: {} value '{}' is not a valid "
                            + "ISO-8601 Duration — falling back to default {} ({})",
                    SETTING_KEY, raw, DEFAULT_MAX_COOLDOWN, e.toString());
            return DEFAULT_MAX_COOLDOWN;
        }
    }

    /** Clamp a duration to {@link #getMax}. {@code null} stays {@code null}. */
    public @Nullable Duration cap(@Nullable Duration in,
                                  @Nullable String tenantId,
                                  @Nullable String projectId,
                                  @Nullable String processId) {
        if (in == null) return null;
        Duration max = getMax(tenantId, projectId, processId);
        return in.compareTo(max) > 0 ? max : in;
    }

    /** Clamp an instant to {@code now + getMax(...)}. {@code null} stays {@code null}. */
    public @Nullable Instant capEta(@Nullable Instant eta,
                                    @Nullable String tenantId,
                                    @Nullable String projectId,
                                    @Nullable String processId) {
        if (eta == null) return null;
        Instant max = Instant.now().plus(getMax(tenantId, projectId, processId));
        return eta.isAfter(max) ? max : eta;
    }
}
