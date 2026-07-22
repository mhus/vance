package de.mhus.vance.shared.compose;

import org.jspecify.annotations.Nullable;

/**
 * Parked in-flight run marker carried in a manifest's managed {@code $run:}
 * block, so a reload can resume polling. Mirrors the TypeScript
 * {@code ComposeRunMarker} ({@code client/packages/shared/src/damogran.ts}).
 */
public record ComposeRunMarker(String id, @Nullable String startedAt) {}
