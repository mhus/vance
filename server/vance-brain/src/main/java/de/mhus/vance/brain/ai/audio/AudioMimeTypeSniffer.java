package de.mhus.vance.brain.ai.audio;

/**
 * Magic-byte mime detection for generated audio. Providers must not
 * trust the requested or advertised format — OpenRouter's Lyria
 * endpoint, for example, answers a {@code format: "wav"} request with
 * MP3 bytes (verified live 2026-10-05). A wrong guess produces a document
 * that no player opens, so the bytes themselves are the authority.
 */
public final class AudioMimeTypeSniffer {

    private AudioMimeTypeSniffer() {}

    /**
     * Sniff the mime type of {@code bytes}, falling back to
     * {@code fallbackMime} when the head of the payload matches
     * nothing known. Never returns {@code null}.
     */
    public static String sniff(byte[] bytes, String fallbackMime) {
        if (bytes == null || bytes.length < 4) {
            return fallbackMime;
        }
        // ID3 tag → MPEG audio (mp3); bare mp3 frames start with
        // 0xFF 0xFB / 0xFF 0xF3 / 0xFF 0xF2.
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return "audio/mpeg";
        }
        // RIFF .... WAVE
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'A'
                && bytes[10] == 'V'
                && bytes[11] == 'E') {
            return "audio/wav";
        }
        // fLaC
        if (bytes[0] == 'f' && bytes[1] == 'L' && bytes[2] == 'a' && bytes[3] == 'C') {
            return "audio/flac";
        }
        // OggS (vorbis / opus)
        if (bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "audio/ogg";
        }
        // .... ftyp (mp4 container — m4a/aac)
        if (bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return "audio/mp4";
        }
        return fallbackMime;
    }
}
