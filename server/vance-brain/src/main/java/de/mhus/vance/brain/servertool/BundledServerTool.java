package de.mhus.vance.brain.servertool;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable parsed entry from {@code server-tools.yaml}. Drives the
 * idempotent bootstrap that materialises tenant-wide defaults into
 * the {@code _vance} system project.
 *
 * <p>{@code disabledSubTools} and {@code defaultDeferred} are
 * pack-related fields — see {@code ServerToolDocument} for semantics.
 * Singleton tools (doc_lookup) leave them empty / false.
 */
public record BundledServerTool(
        String name,
        String type,
        String description,
        Map<String, Object> parameters,
        List<String> labels,
        boolean enabled,
        boolean primary,
        Set<String> disabledSubTools,
        boolean defaultDeferred) {
}
