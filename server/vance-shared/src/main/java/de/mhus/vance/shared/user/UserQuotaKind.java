package de.mhus.vance.shared.user;

/**
 * The two account quotas of a tenant. Which one an account counts against is
 * decided by {@link UserDocument#isServiceAccount()} — the field, never the
 * name: a tombstone {@code _deleted_<name>} carries the service-account prefix
 * as well ({@code specification/public/user-maintenance.md}).
 */
public enum UserQuotaKind {

    /** Accounts of people ({@code serviceAccount == false}). */
    SEATS("seats", "user.quota.seats"),

    /**
     * Service accounts ({@code serviceAccount == true}) without the
     * Vance-internal {@code _vance-*} identities, which belong to the
     * operation, not to the tenant.
     */
    SERVICE_ACCOUNTS("serviceAccounts", "user.quota.serviceAccounts");

    private final String wireName;
    private final String settingKey;

    UserQuotaKind(String wireName, String settingKey) {
        this.wireName = wireName;
        this.settingKey = settingKey;
    }

    /** Name in error bodies and DTOs. */
    public String wireName() {
        return wireName;
    }

    /** Setting key on the {@code _tenant} project. */
    public String settingKey() {
        return settingKey;
    }

    public static UserQuotaKind forServiceAccount(boolean serviceAccount) {
        return serviceAccount ? SERVICE_ACCOUNTS : SEATS;
    }

    public static UserQuotaKind of(UserDocument user) {
        return forServiceAccount(user.isServiceAccount());
    }
}
