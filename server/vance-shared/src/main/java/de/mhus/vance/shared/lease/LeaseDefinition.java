package de.mhus.vance.shared.lease;

import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The configured description of one leaseable resource — the parsed shape
 * of a {@code _vance/config/lease/<name>.yaml} document (see
 * {@code planning/lease-system.md} §4).
 *
 * <p>A definition carries the caps of the four quota levels
 * ({@code global} / {@code tenant} / {@code project} / {@code user}) plus
 * the two timing knobs {@code ttlSeconds} and {@code waitMs}. Absent caps
 * are {@code null} = pass-through on that level; {@code ttlSeconds} and
 * {@code waitMs} stay {@code null} through the layer merge (a layer that
 * does not mention the field must not shadow an inner layer that does)
 * and resolve to the defaults only at acquire time. A definition without
 * a single cap makes {@link ResourceLeaseService#acquire} return a NOOP
 * lease (no Mongo traffic).
 *
 * <p>Caps are at least 1 — a cap of 0 would not mean "blocked" but let
 * the first acquisition create the counter; a configuration that wants a
 * resource off has to say so in the consumer ({@code tex.lease: none}).
 *
 * <p>Instances are immutable and safe to cache.
 */
public record LeaseDefinition(
        String name,
        @Nullable String resource,
        @Nullable Long maxGlobal,
        @Nullable Long maxTenant,
        @Nullable Long maxProject,
        @Nullable Long maxUser,
        @Nullable Long ttlSeconds,
        @Nullable Long waitMs) {

    /**
     * Allowed lease names — the name becomes a document path segment
     * ({@code _vance/config/lease/<name>.yaml}), so no separators, no dots.
     */
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    public LeaseDefinition {
        requireCap("global", maxGlobal);
        requireCap("tenant", maxTenant);
        requireCap("project", maxProject);
        requireCap("user", maxUser);
    }

    /** Whether {@code name} is a valid lease name ({@link #NAME_PATTERN}). */
    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /** Defaults applied at acquire time when no layer configures the field. */
    public static final long DEFAULT_TTL_SECONDS = 120;

    public static final long DEFAULT_WAIT_MS = 10_000;

    /** Resource key — the counter identity; defaults to the definition name. */
    public String resourceKey() {
        return resource == null || resource.isBlank() ? name : resource;
    }

    /** Whether any level carries a cap — {@code false} means NOOP lease. */
    public boolean hasAnyCap() {
        return maxGlobal != null || maxTenant != null || maxProject != null || maxUser != null;
    }

    public long ttlSecondsOrDefault() {
        return ttlSeconds == null || ttlSeconds < 1 ? DEFAULT_TTL_SECONDS : ttlSeconds;
    }

    public long waitMsOrDefault() {
        // 0 is honoured (fail-fast acquire), only null/negative fall back.
        return waitMs == null || waitMs < 0 ? DEFAULT_WAIT_MS : waitMs;
    }

    /**
     * Parses one layer's YAML. Unknown keys are ignored (forward
     * compatibility); missing keys stay {@code null} so the layer merge
     * in {@link LeaseDefinitionLoader} can tell "not set here" apart from
     * "set to a value". A value that is present but not a number is an
     * error, never "unset" — a typo must not silently drop a cap.
     *
     * @throws IllegalArgumentException on a non-numeric value or a cap below 1
     */
    public static LeaseDefinition fromLayer(String name, @Nullable Map<String, Object> yaml) {
        if (yaml == null) {
            return new LeaseDefinition(name, null, null, null, null, null, null, null);
        }
        Map<String, Object> params = yaml;
        @SuppressWarnings("unchecked")
        Map<String, Object> levels = params.get("levels") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new LeaseDefinition(
                name,
                stringOrNull(params.get("resource")),
                longOrNull("levels.global", levels.get("global")),
                longOrNull("levels.tenant", levels.get("tenant")),
                longOrNull("levels.project", levels.get("project")),
                longOrNull("levels.user", levels.get("user")),
                longOrNull("ttlSeconds", params.get("ttlSeconds")),
                longOrNull("waitMs", params.get("waitMs")));
    }

    private static void requireCap(String level, @Nullable Long cap) {
        if (cap != null && cap < 1) {
            throw new IllegalArgumentException("lease cap 'levels." + level + "' must be at least 1, got " + cap);
        }
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    private static @Nullable Long longOrNull(String field, @Nullable Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            if (s.isBlank()) return null;
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("lease field '" + field + "' is not a number: '" + s + "'", e);
            }
        }
        throw new IllegalArgumentException("lease field '" + field + "' is not a number: " + raw);
    }
}
