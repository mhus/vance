package de.mhus.vance.brain.ai.audio;

import org.jspecify.annotations.Nullable;

/**
 * Materialised audio input for a transcription call — already resolved
 * from a document (or a local file for internal callers) with mime
 * type and bytes. Providers do no document I/O.
 *
 * @param data   the audio bytes
 * @param format wire format identifier derived from the mime type
 *               ({@code wav}, {@code mp3}, {@code m4a}, …) — what the
 *               provider's {@code input_audio.format} field expects
 * @param name   short display name (file name) for logs / error
 *               messages, or {@code null}
 */
public record AudioSource(
        byte[] data, String format, @Nullable String name) {

    public AudioSource {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("audio data is empty");
        }
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("format is blank");
        }
    }

    /**
     * Derive the wire format from a mime type. Falls back to the
     * subtype (after {@code audio/}) for anything unrecognised, so an
     * exotic container still travels with a plausible label.
     */
    public static String formatForMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "wav";
        }
        String mime = mimeType.toLowerCase(java.util.Locale.ROOT).trim();
        int semi = mime.indexOf(';');
        if (semi > 0) {
            mime = mime.substring(0, semi).trim();
        }
        return switch (mime) {
            case "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a";
            case "audio/ogg", "audio/ogg; codecs=opus", "audio/opus" -> "ogg";
            case "audio/webm" -> "webm";
            case "audio/flac", "audio/x-flac" -> "flac";
            case "audio/pcm", "audio/L16" -> "wav";
            default -> {
                String subtype = mime.startsWith("audio/") ? mime.substring("audio/".length()) : "wav";
                yield subtype.isEmpty() ? "wav" : subtype;
            }
        };
    }
}
