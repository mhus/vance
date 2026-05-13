package de.mhus.vance.brain.hooks;

import de.mhus.vance.shared.settings.SettingService;
import org.jspecify.annotations.Nullable;

/**
 * Read-only setting view scoped to one hook run. Wraps
 * {@link SettingService}'s cascade lookup with the current
 * {@code (tenantId, projectId)} pre-bound so the script can't ask for
 * a different tenant's value.
 */
public final class HookSettingsView {

    private final SettingService settings;
    private final String tenantId;
    private final @Nullable String projectId;

    public HookSettingsView(
            SettingService settings, String tenantId, @Nullable String projectId) {
        this.settings = settings;
        this.tenantId = tenantId;
        this.projectId = projectId;
    }

    /**
     * Cascade lookup along {@code project → _vance}. Returns
     * {@code null} when nothing is set. Password-typed values are
     * returned as decrypted plaintext via
     * {@link SettingService#getDecryptedPasswordCascade} when the key
     * doesn't resolve to a plain string — supports webhook secrets
     * stored under {@code password}-typed keys.
     */
    public @Nullable String read(String key) {
        String plain = settings.getStringValueCascade(tenantId, projectId, null, key);
        if (plain != null) return plain;
        return settings.getDecryptedPasswordCascade(tenantId, projectId, null, key);
    }
}
