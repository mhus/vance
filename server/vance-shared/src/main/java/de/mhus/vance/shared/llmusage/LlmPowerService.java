package de.mhus.vance.shared.llmusage;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Storage of the LLM power switches: a main switch per tenant and one switch
 * per user, both in the tenant's {@code _tenant} project.
 *
 * <h2>Only "off" is written</h2>
 *
 * {@value #KEY_TENANT} and {@value #USER_PREFIX}{@code <login>} exist only
 * while the switch is off and are deleted when it goes back on. Default "on"
 * therefore needs no bootstrap, and one prefix query reads the whole state of a
 * tenant. Two admins switching two different users at the same moment write two
 * different documents — a list in one setting would lose one of the writes.
 *
 * <h2>Read directly, never through a cascade</h2>
 *
 * The keys live in {@code _tenant} and are read only there. Through the user
 * cascade a value in the user's own {@code _user_<login>} project could shadow
 * the switch, and the user is exactly who must not be able to turn it back on.
 *
 * <p>Plain reads and writes, no cache: the brain's admission path caches the
 * state itself and refreshes it in the background.
 */
@Service
@RequiredArgsConstructor
public class LlmPowerService {

    /** Main switch of the tenant; present only while it is off. */
    public static final String KEY_TENANT = "llm.power.enabled";

    /** Per-user switch, followed by the login; present only while it is off. */
    public static final String USER_PREFIX = "llm.power.user.";

    static final String KEY_PREFIX = "llm.power.";

    private final SettingService settingService;

    /** The current switches of {@code tenantId}, read from the store. */
    public LlmPowerState load(String tenantId) {
        LlmPowerState.Switch tenantOff = null;
        Map<String, LlmPowerState.Switch> usersOff = new HashMap<>();
        for (SettingDocument doc : settingService.findByPrefix(
                tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, KEY_PREFIX)) {
            if (!isOff(doc.getValue())) {
                continue;
            }
            LlmPowerState.Switch off = new LlmPowerState.Switch(doc.getUpdatedAt(), doc.getDescription());
            String key = doc.getKey();
            if (KEY_TENANT.equals(key)) {
                tenantOff = off;
            } else if (key.startsWith(USER_PREFIX) && key.length() > USER_PREFIX.length()) {
                usersOff.put(key.substring(USER_PREFIX.length()), off);
            }
        }
        return new LlmPowerState(tenantOff, usersOff);
    }

    /** Switch the whole tenant on or off. */
    public void setTenantPower(String tenantId, boolean on, @Nullable String actor) {
        write(tenantId, KEY_TENANT, on, actor);
    }

    /** Switch one user of the tenant on or off. */
    public void setUserPower(String tenantId, String userId, boolean on, @Nullable String actor) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        write(tenantId, USER_PREFIX + userId, on, actor);
    }

    private void write(String tenantId, String key, boolean on, @Nullable String actor) {
        if (on) {
            settingService.delete(
                    tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, key);
            return;
        }
        settingService.setAs(
                tenantId,
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                key,
                Boolean.FALSE.toString(),
                SettingType.BOOLEAN,
                actor == null ? "Switched off" : "Switched off by " + actor,
                actor);
    }

    /**
     * Only an explicit {@code false} is off. A stray {@code true} written by
     * hand through the raw settings editor reads as on, like a missing key.
     */
    static boolean isOff(@Nullable String value) {
        return value != null && "false".equalsIgnoreCase(value.trim());
    }
}
