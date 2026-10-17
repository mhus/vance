package de.mhus.vance.shared.llmusage;

import de.mhus.vance.shared.user.maintenance.UserDataHandler;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The user's LLM power switch — {@code llm.power.user.<login>} in the tenant's
 * {@code _tenant} project, present only while the user is switched off.
 *
 * <p>A restriction, not authority, but it follows the same rule as authority
 * on a delete: <b>removed</b>, because a name comes back, and the next account
 * minted under it must not start out switched off by a decision about somebody
 * else. On a rename it <b>moves</b> with the login: same person, and a
 * switched-off user must not get back on by being renamed.
 *
 * <p>Goes through {@link LlmPowerService}, the owner of the keys, instead of
 * the settings collection: the login is part of a key in a project-scoped
 * setting, not a user-naming field, so no field mapping could express it.
 *
 * <p>Spec: {@code specification/public/llm-resource-management.md} §5b,
 * {@code specification/public/user-maintenance.md}.
 */
@Component
@RequiredArgsConstructor
public class LlmPowerUserDataHandler implements UserDataHandler {

    private final LlmPowerService powerService;

    @Override
    public String id() {
        return "llm-power-user-switch";
    }

    /**
     * Empty: the rows live in {@code settings}, which the settings handlers
     * answer for; claiming it here would hide nothing the probe could find,
     * because no user-naming field points at these rows.
     */
    @Override
    public Set<String> collections() {
        return Set.of();
    }

    @Override
    public int order() {
        return 1800;
    }

    @Override
    public long count(String tenantId, String userName) {
        return powerService.countUserSwitch(tenantId, userName);
    }

    @Override
    public long delete(String tenantId, String userName) {
        return powerService.deleteUserSwitch(tenantId, userName);
    }

    @Override
    public long rename(String tenantId, String userName, String newUserName) {
        return powerService.renameUserSwitch(tenantId, userName, newUserName);
    }
}
