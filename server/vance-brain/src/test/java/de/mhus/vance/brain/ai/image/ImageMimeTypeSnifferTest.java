package de.mhus.vance.brain.ai.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the magic-byte sniffer. Byte fixtures below are the
 * real file signatures (PNG 8-byte header, JPEG SOI+APP0, RIFF/WEBP,
 * GIF, BMP) — not illustrative shortcuts.
 */
class ImageMimeTypeSnifferTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
    private static final byte[] BMP = {'B', 'M', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Test
    void sniffs_all_bundled_signatures() {
        assertThat(ImageMimeTypeSniffer.sniff(null, PNG)).isEqualTo("image/png");
        assertThat(ImageMimeTypeSniffer.sniff(null, JPEG)).isEqualTo("image/jpeg");
        assertThat(ImageMimeTypeSniffer.sniff(null, WEBP)).isEqualTo("image/webp");
        assertThat(ImageMimeTypeSniffer.sniff(null, GIF)).isEqualTo("image/gif");
        assertThat(ImageMimeTypeSniffer.sniff(null, BMP)).isEqualTo("image/bmp");
    }

    @Test
    void reported_type_wins_over_bytes() {
        // A carrier that DID transport a mime type is authoritative;
        // sniffing exists to replace assumptions, not reports.
        assertThat(ImageMimeTypeSniffer.sniff("image/webp", JPEG)).isEqualTo("image/webp");
    }

    @Test
    void unknown_bytes_return_null() {
        assertThat(ImageMimeTypeSniffer.sniff(null, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}))
                .isNull();
    }

    @Test
    void short_payloads_return_null() {
        // Fewer bytes than the longest signature (12) — not enough
        // information, so the caller's default applies.
        assertThat(ImageMimeTypeSniffer.sniff(null, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}))
                .isNull();
        assertThat(ImageMimeTypeSniffer.sniff(null, new byte[0])).isNull();
        assertThat(ImageMimeTypeSniffer.sniff(null, null)).isNull();
    }

    @Test
    void resolve_or_sniff_applies_fallback() {
        assertThat(ImageMimeTypeSniffer.resolveOrSniff(null, new byte[] {1, 2, 3}, "image/png"))
                .isEqualTo("image/png");
        assertThat(ImageMimeTypeSniffer.resolveOrSniff(null, JPEG, "image/png")).isEqualTo("image/jpeg");
    }
}
