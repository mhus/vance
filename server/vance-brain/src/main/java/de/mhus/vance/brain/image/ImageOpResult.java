package de.mhus.vance.brain.image;

/**
 * Result of one image-manipulation call — the shape every
 * {@code image_*} tool wrapper turns into its success response.
 * Fields match the contract in
 * {@code specification/image-manipulation.md} §4.
 */
public record ImageOpResult(
        String path,
        String mimeType,
        int width,
        int height,
        long sizeBytes,
        long durationMs) {}
