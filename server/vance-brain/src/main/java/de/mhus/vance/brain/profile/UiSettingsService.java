package de.mhus.vance.brain.profile;

import de.mhus.vance.shared.access.WebUiCookies;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.settings.TimezoneResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The user's own UI settings — the {@code webui.*} keys plus a small
 * allowlist of self-service extras. One definition for the two places that
 * hand them out: the profile ({@code ProfileController}) and
 * {@code GET /me}.
 */
@Service
@RequiredArgsConstructor
public class UiSettingsService {

    /**
     * Extra keys (outside the {@code webui.*} prefix) the user may set
     * themselves. Keep this small and deliberate — every entry is something
     * the user is trusted to set without admin involvement.
     */
    public static final Set<String> SELF_SERVICE_EXTRA_KEYS =
            Set.of(LanguageResolver.Keys.CHAT_LANGUAGE, TimezoneResolver.Keys.DISPLAY_TIMEZONE);

    private final SettingService settingService;

    /** The user-scope {@code webui.*} settings plus the set extras; absent keys are unset. */
    public Map<String, String> load(String tenantId, String username) {
        Map<String, String> merged = new LinkedHashMap<>(
                settingService.findUserSettingsByPrefix(tenantId, username, WebUiCookies.SETTINGS_PREFIX));
        // The extras live outside the webui.* prefix, so the prefix query
        // misses them — read each explicitly from the user scope.
        for (String key : SELF_SERVICE_EXTRA_KEYS) {
            String v = settingService.getUserStringValue(tenantId, username, key);
            if (v != null) merged.put(key, v);
        }
        return merged;
    }
}
