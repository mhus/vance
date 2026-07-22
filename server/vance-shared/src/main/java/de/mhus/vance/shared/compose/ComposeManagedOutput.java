package de.mhus.vance.shared.compose;

import org.jspecify.annotations.Nullable;

/**
 * One resolved compose output artifact carried in a manifest's managed
 * {@code $output:} block. Mirrors the TypeScript {@code ComposeOutputView}
 * ({@code client/packages/shared/src/damogran.ts}). {@code path} and
 * {@code uri} are required; {@code kind} and {@code title} are optional.
 */
public record ComposeManagedOutput(
        String path,
        String uri,
        @Nullable String kind,
        @Nullable String title) {}
