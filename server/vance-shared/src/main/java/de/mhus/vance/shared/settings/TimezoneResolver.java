package de.mhus.vance.shared.settings;

import java.time.DateTimeException;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the display timezone a user expects wall-clock output in —
 * the "Current date" prompt block, the {@code current_time} tool
 * default, and the zone a freshly created scheduler is pinned to.
 *
 * <p>Cascade {@code _user_<userId> → _tenant}: a personal preference
 * with a tenant-wide default fallback. The {@code <projectId>}-layer is
 * intentionally skipped — a timezone belongs to the human, not the
 * project (mirrors {@link LanguageResolver.Keys#WEBUI_LANGUAGE}). When
 * neither layer carries the key, callers get {@link #DEFAULT_ZONE}
 * ({@code UTC}) — preserving the historical server-neutral behaviour.
 *
 * <p>Stored as {@value Keys#DISPLAY_TIMEZONE} in the per-user system
 * project via {@link SettingService}. The Web-UI writes it through the
 * self-service profile endpoint; tenants set a default through the admin
 * settings surface on the {@code _tenant} project.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TimezoneResolver {

    /** Final fallback when neither user nor tenant configured a timezone. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

    /** Setting-key constants — public so callers can read/write directly via SettingService when needed. */
    public static final class Keys {
        /** Display timezone. Scope: user → tenant. IANA zone id (e.g. {@code Europe/Berlin}). */
        public static final String DISPLAY_TIMEZONE = "display.timezone";

        private Keys() {}
    }

    private final SettingService settingService;

    /**
     * Raw resolved value from the user → tenant cascade, or {@code null}
     * when nothing is configured anywhere. Not validated against the
     * IANA database — use {@link #zoneId} / {@link #findZoneId} for a
     * parsed {@link ZoneId}.
     */
    public @Nullable String findTimezone(String tenantId, @Nullable String userId) {
        if (userId == null || userId.isBlank()) {
            // No user context (e.g. anonymous/admin flows) — fall back to
            // the tenant default only. getUserStringValueWithDefault needs
            // a userId, so read the tenant layer directly.
            return settingService.getStringValue(tenantId,
                    SettingService.SCOPE_PROJECT,
                    de.mhus.vance.shared.home.HomeBootstrapService.TENANT_PROJECT_NAME,
                    Keys.DISPLAY_TIMEZONE);
        }
        return settingService.getUserStringValueWithDefault(
                tenantId, userId, Keys.DISPLAY_TIMEZONE);
    }

    /**
     * Parsed {@link ZoneId} from the cascade, or {@code null} when
     * nothing is configured (or the configured value is not a valid IANA
     * zone). Callers that want a hard default use {@link #zoneId}.
     */
    public @Nullable ZoneId findZoneId(String tenantId, @Nullable String userId) {
        String raw = findTimezone(tenantId, userId);
        if (raw == null || raw.isBlank()) return null;
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException e) {
            // A stale / hand-edited setting shouldn't break prompt rendering
            // or scheduler creation — degrade to "no opinion".
            log.trace("Ignoring invalid display.timezone '{}' for tenant='{}' user='{}': {}",
                    raw, tenantId, userId, e.getMessage());
            return null;
        }
    }

    /**
     * Parsed {@link ZoneId} from the cascade, defaulting to
     * {@link #DEFAULT_ZONE} ({@code UTC}) when nothing valid is
     * configured. This is the variant prompt rendering and the
     * {@code current_time} tool use.
     */
    public ZoneId zoneId(String tenantId, @Nullable String userId) {
        ZoneId z = findZoneId(tenantId, userId);
        return z == null ? DEFAULT_ZONE : z;
    }
}
