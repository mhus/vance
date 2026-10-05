package de.mhus.vance.brain.audio;

import org.jspecify.annotations.Nullable;

/**
 * Result of one audio-edit call that wrote a document (trim, mix,
 * concat, convert) — the shape every {@code audio_*} edit tool wrapper
 * turns into its success response.
 *
 * @param path            document path written
 * @param mimeType        sniffed mime type of the written bytes
 * @param sizeBytes       written size
 * @param durationSeconds duration of the result as reported by
 *                        ffprobe, or {@code null} when unreadable
 * @param durationMs      wall-clock time of the whole call
 */
public record AudioOpResult(
        String path,
        String mimeType,
        long sizeBytes,
        @Nullable Double durationSeconds,
        long durationMs) {}
