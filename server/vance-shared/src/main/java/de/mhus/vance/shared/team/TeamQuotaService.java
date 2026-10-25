package de.mhus.vance.shared.team;

import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Per-tenant cap on the number of teams. The limit lives as a setting
 * ({@value #KEY_MAX_ACTIVE}) on the tenant's {@code _tenant} project, next to
 * {@code project.quota.*} and {@code user.quota.*}. Unset, blank or
 * {@code <= 0} means no limit — the default.
 *
 * <p>What counts is {@link TeamService#countEnabled}: a disabled team frees
 * its place, the way a DISABLED account does for the user quota.
 *
 * <p>Enforced by {@link TeamService} itself where a team <em>becomes</em>
 * counted — create, and enabling a disabled team. The overloads with
 * {@code enforceQuota = false} are for seeding and the admin shell's
 * {@code --ignore-quota}.
 *
 * <p>Count and insert are two steps, so two creates racing for the last place
 * can both pass. That overshoot by one is accepted: this is an administrative
 * ceiling, not an accounting invariant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamQuotaService {

    /** Setting key on the {@code _tenant} project. */
    public static final String KEY_MAX_ACTIVE = "team.quota.maxActive";

    private final TeamService teamService;
    private final SettingService settingService;

    /** Current count and limit for {@code tenantId}. */
    public Status status(String tenantId) {
        return new Status(teamService.countEnabled(tenantId), maxActive(tenantId));
    }

    /**
     * Throws when one more enabled team would exceed the limit. No-op for an
     * unlimited tenant.
     *
     * @throws TeamQuotaExceededException when the tenant is at its limit
     */
    public void checkOneMore(String tenantId) {
        int limit = maxActive(tenantId);
        if (limit <= 0) {
            return;
        }
        long active = teamService.countEnabled(tenantId);
        if (active >= limit) {
            log.trace("team quota exceeded tenant='{}' active={} limit={}", tenantId, active, limit);
            throw new TeamQuotaExceededException(active, limit);
        }
    }

    /**
     * Checks a change of the {@code enabled} flag. Only enabling a disabled
     * team makes it counted again; every other change keeps the count.
     *
     * @throws TeamQuotaExceededException when enabling would exceed the limit
     */
    public void checkEnabledChange(String tenantId, TeamDocument team, boolean enabled) {
        if (enabled && !team.isEnabled()) {
            checkOneMore(tenantId);
        }
    }

    private int maxActive(String tenantId) {
        int raw = settingService.getIntValue(
                tenantId, SettingService.SCOPE_PROJECT, ProjectService.TENANT_PROJECT_NAME, KEY_MAX_ACTIVE, 0);
        return Math.max(0, raw);
    }

    /** {@code limit == 0} means unlimited. */
    public record Status(long active, int limit) {

        public boolean overLimit() {
            return limit > 0 && active > limit;
        }
    }

    public static class TeamQuotaExceededException extends RuntimeException {
        private final long active;
        private final int limit;

        public TeamQuotaExceededException(long active, int limit) {
            super("Team quota reached: " + active + " of " + limit
                    + " — disable or delete a team or ask an administrator to raise the limit");
            this.active = active;
            this.limit = limit;
        }

        public long active() {
            return active;
        }

        public int limit() {
            return limit;
        }
    }
}
