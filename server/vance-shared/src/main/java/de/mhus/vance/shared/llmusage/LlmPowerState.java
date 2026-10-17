package de.mhus.vance.shared.llmusage;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The LLM power switches of one tenant, as read in one go: the tenant-wide
 * main switch and the users that are switched off.
 *
 * <p>Only switches that are <em>off</em> exist as settings — "on" is the
 * absence of a setting — so {@link #usersOff} lists exactly the users that
 * may not call a model, and a tenant nobody ever touched reads as
 * {@link #ALL_ON}.
 *
 * @param tenantOff {@code null} while the tenant is on; otherwise when and
 *                  by whom it was switched off
 * @param usersOff  login → when and by whom that user was switched off
 */
public record LlmPowerState(@Nullable Switch tenantOff, Map<String, Switch> usersOff) {

    public static final LlmPowerState ALL_ON = new LlmPowerState(null, Map.of());

    public LlmPowerState {
        usersOff = Map.copyOf(usersOff);
    }

    /** The main switch of the tenant. */
    public boolean tenantOn() {
        return tenantOff == null;
    }

    /** Whether {@code userId}'s own switch is on; says nothing about the main switch. */
    public boolean userOn(String userId) {
        return !usersOff.containsKey(userId);
    }

    /**
     * One switch in the off position.
     *
     * @param since when it was switched off, {@code null} if the store did not say
     * @param note  who switched it off, as written at the time — a display
     *              string, not a key
     */
    public record Switch(@Nullable Instant since, @Nullable String note) {}
}
