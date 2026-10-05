package de.mhus.vance.brain.audio;

import org.jspecify.annotations.Nullable;

/**
 * Result of {@code audio_info} — probe metadata of an existing audio
 * document. Nothing is written; the numbers are what every trim/mix
 * plan should be based on.
 *
 * @param path            document path probed
 * @param mimeType        mime type of the document
 * @param format          container as reported by ffprobe (e.g. {@code mp3}, {@code wav})
 * @param codec           audio codec (e.g. {@code mp3}, {@code pcm_s16le})
 * @param channels        channel count, or {@code null} when unreadable
 * @param sampleRate      sample rate in Hz, or {@code null} when unreadable
 * @param sizeBytes       stored size
 * @param durationSeconds duration in seconds, or {@code null} when unreadable
 */
public record AudioProbeResult(
        String path,
        String mimeType,
        @Nullable String format,
        @Nullable String codec,
        @Nullable Integer channels,
        @Nullable Integer sampleRate,
        long sizeBytes,
        @Nullable Double durationSeconds) {}
