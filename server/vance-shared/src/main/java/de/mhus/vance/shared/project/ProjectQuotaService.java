package de.mhus.vance.shared.project;

import de.mhus.vance.shared.settings.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Per-tenant cap on the number of active projects. The limit lives as a
 * setting ({@value #KEY_MAX_ACTIVE}) on the tenant's {@code _tenant}
 * project, next to {@code storage.quota.*} and {@code ai.llm.budget.*}.
 * Unset, blank or {@code <= 0} means no limit — the default.
 *
 * <p>"Active" is {@link ProjectService#countActive}: every project that is
 * not SYSTEM and not CLOSED. Suspended projects count; the brain suspends and
 * resumes them on its own, so a limit on running projects would lock users
 * out of projects they already have.
 *
 * <p>Checked where a project <em>becomes</em> active — create and reopen in
 * {@code ProjectLifecycleService} — and in the admin shell's
 * {@code project create} / {@code project reopen}, where the operator may
 * step past it with {@code --ignore-quota}.
 *
 * <p>Count and insert are two steps, so two creates racing for the last slot
 * can both pass. That overshoot by one is accepted: this is an
 * administrative ceiling, not an accounting invariant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectQuotaService {

    /** Setting key on the {@code _tenant} project. */
    public static final String KEY_MAX_ACTIVE = "project.quota.maxActive";

    private final ProjectService projectService;
    private final SettingService settingService;

    /** Current count and limit for {@code tenantId}. */
    public Status status(String tenantId) {
        return new Status(projectService.countActive(tenantId), maxActive(tenantId));
    }

    /**
     * Throws when one more active project would exceed the limit. No-op for
     * an unlimited tenant.
     *
     * @throws ProjectQuotaExceededException when the tenant is at its limit
     */
    public void checkOneMore(String tenantId) {
        int limit = maxActive(tenantId);
        if (limit <= 0) {
            return;
        }
        long active = projectService.countActive(tenantId);
        if (active >= limit) {
            log.trace("project quota exceeded tenant='{}' active={} limit={}", tenantId, active, limit);
            throw new ProjectQuotaExceededException(active, limit);
        }
    }

    private int maxActive(String tenantId) {
        int raw = settingService.getIntValue(
                tenantId, SettingService.SCOPE_PROJECT, ProjectService.TENANT_PROJECT_NAME, KEY_MAX_ACTIVE, 0);
        return Math.max(0, raw);
    }

    /** {@code limit == 0} means unlimited. */
    public record Status(long active, int limit) {}

    public static class ProjectQuotaExceededException extends RuntimeException {
        private final long active;
        private final int limit;

        public ProjectQuotaExceededException(long active, int limit) {
            super("Active project quota reached: " + active + " of " + limit
                    + " — archive a project or ask an administrator to raise the limit");
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
