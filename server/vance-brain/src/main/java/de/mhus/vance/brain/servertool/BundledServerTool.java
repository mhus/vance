package de.mhus.vance.brain.servertool;

import java.util.List;
import java.util.Map;

/**
 * Immutable parsed entry from {@code server-tools.yaml}. Drives the
 * idempotent bootstrap that materialises tenant-wide defaults into
 * the {@code _vance} system project.
 */
public record BundledServerTool(
        String name,
        String type,
        String description,
        Map<String, Object> parameters,
        List<String> labels,
        boolean enabled,
        boolean primary) {
}
