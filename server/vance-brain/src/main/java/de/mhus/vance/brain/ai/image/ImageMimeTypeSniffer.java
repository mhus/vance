package de.mhus.vance.brain.ai.image;

import org.jspecify.annotations.Nullable;

/**
 * Sniffs the image format from the leading bytes of a provider payload.
 *
 * <p>Why this exists: the OpenAI images wire carries no mime type, so a
 * provider cannot always <em>report</em> what it returns — the
 * providers' fallback assumes PNG when the adapter's model object has
 * none. That assumption predates OpenAI-wire <em>gateways</em>
 * (Cortecs, OpenRouter, …): those serve vendor backends that answer
 * with JPEG or WebP bytes, and a PNG-typed document over JPEG bytes
 * renders fine in browsers (content sniffing) but lies to every
 * mime-sensitive reader — download extensions, converters, anything
 * that trusts the stored {@code mimeType}.
 *
 * <p>The sniffer reads the smallest signature set that the bundled
 * image providers can actually emit: PNG, JPEG, WebP, GIF, BMP. It
 * never <em>overrides</em> a mime type the provider did report — it
 * only replaces format assumptions (see
 * {@link #resolveOrSniff(String, byte[], String)}).
 */
public final class ImageMimeTypeSniffer {

    private static final String PNG = "image/png";
    private static final String JPEG = "image/jpeg";
    private static final String WEBP = "image/webp";
    private static final String GIF = "image/gif";
    private static final String BMP = "image/bmp";

    private ImageMimeTypeSniffer() {}

    /**
     * Resolves the payload's mime type: a reported type wins, otherwise
     * the leading bytes decide.
     *
     * @param reported the mime type the provider reported, if any
     *                 ({@code null}/blank when the wire or adapter
     *                 carries none — the OpenAI images wire never does)
     * @param payload  the image bytes; only a small prefix is read
     * @return the sniffed or reported mime type, or {@code null} when
     *         the bytes match no known signature — the caller decides
     *         what an unidentified payload means (providers keep their
     *         PNG default for that case)
     */
    public static @Nullable String sniff(@Nullable String reported, byte[] payload) {
        if (reported != null && !reported.isBlank()) {
            return reported;
        }
        if (payload == null || payload.length < 12) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((payload[0] & 0xFF) == 0x89
                && (payload[1] & 0xFF) == 0x50
                && (payload[2] & 0xFF) == 0x4E
                && (payload[3] & 0xFF) == 0x47
                && (payload[4] & 0xFF) == 0x0D
                && (payload[5] & 0xFF) == 0x0A
                && (payload[6] & 0xFF) == 0x1A
                && (payload[7] & 0xFF) == 0x0A) {
            return PNG;
        }
        // JPEG: FF D8 FF
        if ((payload[0] & 0xFF) == 0xFF && (payload[1] & 0xFF) == 0xD8 && (payload[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        // WebP: "RIFF" .... "WEBP"
        if ((payload[0] & 0xFF) == 'R'
                && (payload[1] & 0xFF) == 'I'
                && (payload[2] & 0xFF) == 'F'
                && (payload[3] & 0xFF) == 'F'
                && (payload[8] & 0xFF) == 'W'
                && (payload[9] & 0xFF) == 'E'
                && (payload[10] & 0xFF) == 'B'
                && (payload[11] & 0xFF) == 'P') {
            return WEBP;
        }
        // GIF: "GIF87a" / "GIF89a"
        if ((payload[0] & 0xFF) == 'G' && (payload[1] & 0xFF) == 'I' && (payload[2] & 0xFF) == 'F') {
            return GIF;
        }
        // BMP: "BM"
        if ((payload[0] & 0xFF) == 'B' && (payload[1] & 0xFF) == 'M') {
            return BMP;
        }
        return null;
    }

    /**
     * The provider-facing form: prefer the sniffed type; fall back to
     * the caller's default when neither a report nor a signature
     * identifies the payload. A reported type always wins — sniffing
     * must not override information the wire actually carried.
     *
     * @param reported       provider-reported mime type, may be null
     * @param payload        the image bytes (only a prefix is read)
     * @param unknownDefault fallback when nothing identifies the payload
     *                       ({@code "image/png"} in the bundled providers)
     */
    public static String resolveOrSniff(@Nullable String reported, byte[] payload, String unknownDefault) {
        String sniffed = sniff(reported, payload);
        return sniffed != null ? sniffed : unknownDefault;
    }
}
