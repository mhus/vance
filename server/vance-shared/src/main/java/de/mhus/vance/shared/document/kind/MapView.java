package de.mhus.vance.shared.document.kind;

import org.jspecify.annotations.Nullable;

/**
 * Initial viewport for a {@code kind: map} document — what the
 * renderer shows before the user pans / zooms. Both fields are
 * optional; missing values fall back to "fit all features".
 *
 * <p>Spec: {@code specification/doc-kind-map.md} §2.4.
 */
public record MapView(
        @Nullable MapLocation center,
        @Nullable Integer zoom) {
}
