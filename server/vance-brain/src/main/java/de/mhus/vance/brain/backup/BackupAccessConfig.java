package de.mhus.vance.brain.backup;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * The parsed shape of {@code _vance/config/backup-access.yaml} in the
 * tenant's {@code _tenant} project — the per-tenant gate for the
 * {@code backup} integration-token profile
 * ({@code planning/user-backup.md} §7.2).
 *
 * <p>Three fields:
 *
 * <ul>
 *   <li>{@code enabled} — boolean; the master switch. Default
 *       {@code true} when the file is absent (so a fresh tenant can back
 *       up without first writing YAML), {@code false} when explicitly
 *       disabled.</li>
 *   <li>{@code users} — list of account names the policy admits. Empty
 *       list = everyone; non-empty = only those accounts.</li>
 *   <li>{@code idleDays} — the tenant ceiling for a sliding-lifetime
 *       token's {@code idleDays}. The mint refuses to issue a token with
 *       a longer window. Default {@code 90} when the field is absent.
 *       Hard-capped at {@code 365} (the IntegrationTokenController's
 *       fixed-end maximum); a tenant can only narrow the window, never
 *       widen it past that.</li>
 * </ul>
 *
 * <p>Parsing is fail-closed (unlike the MCP equivalent): a missing file
 * means the feature is on, but a malformed file denies all backup
 * access with a WARN log. The asymmetry is deliberate — anyone who
 * wrote the file wanted something specific, and a typo must not turn
 * "lock down to alice" into "everyone".
 */
public record BackupAccessConfig(boolean enabled, List<String> users, int idleDays) {

    /** The default that "file missing" produces — feature on, no user restriction, 90-day ceiling. */
    public static final BackupAccessConfig DEFAULT_ALLOWED = new BackupAccessConfig(true, List.of(), 90);

    /** Hard cap; the {@code IntegrationTokenController} maximum is 365. A tenant can narrow, not widen. */
    public static final int MAX_IDLE_DAYS = 365;

    public BackupAccessConfig {
        users = users == null ? List.of() : List.copyOf(users);
    }

    /**
     * Whether this config admits the given user. {@code enabled: false} is
     * a hard stop; an empty {@code users} list is open to everyone (the
     * tenant admin opted into "all my accounts can back up"); a non-empty
     * list names exactly those allowed.
     */
    public boolean admits(@Nullable String username) {
        if (!enabled) return false;
        if (users.isEmpty()) return true;
        if (username == null || username.isBlank()) return false;
        for (String u : users) {
            if (u.equals(username)) return true;
        }
        return false;
    }

    /** The effective ceiling, clamped to the hard cap. A tenant cannot request more than the global max. */
    public int effectiveIdleDaysCeiling() {
        return Math.min(Math.max(idleDays, 1), MAX_IDLE_DAYS);
    }

    /**
     * Parse a YAML string. Empty/missing body produces
     * {@link #DEFAULT_ALLOWED} (enabled, no user restriction, 90-day
     * ceiling). Malformed input throws — the caller decides whether to
     * warn-and-deny or warn-and-fallback.
     */
    public static BackupAccessConfig parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return DEFAULT_ALLOWED;
        }
        Object root = new Yaml().load(yaml);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException("backup-access: top-level must be a mapping");
        }
        Boolean enabled = readBoolean(map, "enabled");
        List<String> users = readStringList(map, "users");
        Integer idleDays = readInt(map, "idleDays");
        return new BackupAccessConfig(
                enabled == null ? true : enabled, users == null ? List.of() : users, idleDays == null ? 90 : idleDays);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Boolean readBoolean(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<String> readStringList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("backup-access: '" + key + "' must be a list");
        }
        List<String> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new IllegalStateException("backup-access: '" + key + "' entries must be strings");
            }
            out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Integer readInt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("backup-access: '" + key + "' must be a number", ex);
            }
        }
        throw new IllegalStateException("backup-access: '" + key + "' must be a number");
    }
}
