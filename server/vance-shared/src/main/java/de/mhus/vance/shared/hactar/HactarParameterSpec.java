package de.mhus.vance.shared.hactar;

import org.jspecify.annotations.Nullable;

/**
 * Parsed entry from a workflow's {@code parameters:} block (plan §3.1).
 * Used at {@code start()} time to validate and default caller params
 * before they're persisted in {@code StartRecord.params}.
 *
 * @param type Permissive type tag — {@code string} / {@code integer} /
 *             {@code boolean} / {@code object} / {@code array}. The
 *             validator coerces leniently rather than rejecting on
 *             type-mismatch.
 * @param required {@code true} when the caller must supply this param.
 * @param defaultValue Value substituted when the caller omits a non-required param.
 */
public record HactarParameterSpec(
        String type,
        boolean required,
        @Nullable Object defaultValue) {
}
