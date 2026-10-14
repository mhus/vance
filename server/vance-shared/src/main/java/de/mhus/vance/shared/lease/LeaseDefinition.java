package de.mhus.vance.shared.lease;

import java.util.Map;
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
     * "set to a value".
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
                longOrNull(levels.get("global")),
                longOrNull(levels.get("tenant")),
                longOrNull(levels.get("project")),
                longOrNull(levels.get("user")),
                longOrNull(params.get("ttlSeconds")),
                longOrNull(params.get("waitMs")));
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    private static @Nullable Long longOrNull(@Nullable Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
