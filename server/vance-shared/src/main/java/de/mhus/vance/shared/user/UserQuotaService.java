package de.mhus.vance.shared.user;

import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Per-tenant caps on the number of accounts — one for people
 * ({@link UserQuotaKind#SEATS}), one for service accounts
 * ({@link UserQuotaKind#SERVICE_ACCOUNTS}). The limits live as settings on the
 * tenant's {@code _tenant} project, next to {@code project.quota.*}. Unset,
 * blank or {@code <= 0} means no limit — the default.
 *
 * <p>What counts is {@link UserService#countCounted}: every account except a
 * DISABLED one. Only DISABLED frees a place; {@code loginEnabled = false} does
 * not, so there is exactly one signal to turn.
 *
 * <p>Enforced by {@link UserService} itself on every write that makes an
 * account counted — creation, and a status change out of DISABLED. Its plain
 * methods always check; the overloads with {@code enforceQuota = false} are
 * for seeding, the setup wizard and the admin shell's {@code --ignore-quota}
 * ({@code specification/public/user-quota.md} §3).
 *
 * <p>Count and insert are two steps, so two creates racing for the last place
 * can both pass. That overshoot by one is accepted: this is an administrative
 * ceiling, not an accounting invariant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserQuotaService {

    private final UserService userService;
    private final SettingService settingService;

    /** Current count and limit of both quotas for {@code tenantId}. */
    public Status status(String tenantId) {
        return new Status(status(tenantId, UserQuotaKind.SEATS), status(tenantId, UserQuotaKind.SERVICE_ACCOUNTS));
    }

    /** Current count and limit of one quota. */
    public Usage status(String tenantId, UserQuotaKind kind) {
        return new Usage(userService.countCounted(tenantId, kind), limit(tenantId, kind));
    }

    /**
     * Throws when one more counted account of {@code kind} would exceed the
     * limit. No-op for an unlimited quota.
     *
     * @throws UserQuotaExceededException when the tenant is at its limit
     */
    public void checkOneMore(String tenantId, UserQuotaKind kind) {
        int limit = limit(tenantId, kind);
        if (limit <= 0) {
            return;
        }
        long active = userService.countCounted(tenantId, kind);
        if (active >= limit) {
            log.trace("user quota exceeded tenant='{}' kind={} active={} limit={}", tenantId, kind, active, limit);
            throw new UserQuotaExceededException(kind, active, limit);
        }
    }

    /**
     * Checks a status change of an existing account. Only leaving DISABLED
     * makes an account counted again; every other change keeps the count, and
     * a Vance-internal {@code _vance-*} identity is never counted at all.
     *
     * @throws UserQuotaExceededException when reactivating would exceed the limit
     */
    public void checkStatusChange(String tenantId, UserDocument user, UserStatus newStatus) {
        if (user.getStatus() != UserStatus.DISABLED || newStatus == UserStatus.DISABLED) {
            return;
        }
        if (user.isServiceAccount() && user.getName().startsWith(UserService.RESERVED_VANCE_PREFIX)) {
            return;
        }
        checkOneMore(tenantId, UserQuotaKind.of(user));
    }

    private int limit(String tenantId, UserQuotaKind kind) {
        int raw = settingService.getIntValue(
                tenantId, SettingService.SCOPE_PROJECT, ProjectService.TENANT_PROJECT_NAME, kind.settingKey(), 0);
        return Math.max(0, raw);
    }

    /** {@code limit == 0} means unlimited. */
    public record Usage(long active, int limit) {

        public boolean overLimit() {
            return limit > 0 && active > limit;
        }
    }

    public record Status(Usage seats, Usage serviceAccounts) {}

    public static class UserQuotaExceededException extends RuntimeException {
        private final UserQuotaKind kind;
        private final long active;
        private final int limit;

        public UserQuotaExceededException(UserQuotaKind kind, long active, int limit) {
            super(message(kind, active, limit));
            this.kind = kind;
            this.active = active;
            this.limit = limit;
        }

        private static String message(UserQuotaKind kind, long active, int limit) {
            String what = kind == UserQuotaKind.SEATS ? "Seat quota" : "Service-account quota";
            String free =
                    kind == UserQuotaKind.SEATS ? "disable or delete a user" : "disable or delete a service account";
            return what + " reached: " + active + " of " + limit + " — " + free
                    + " or ask an administrator to raise the limit";
        }

        public UserQuotaKind kind() {
            return kind;
        }

        public long active() {
            return active;
        }

        public int limit() {
            return limit;
        }
    }
}
