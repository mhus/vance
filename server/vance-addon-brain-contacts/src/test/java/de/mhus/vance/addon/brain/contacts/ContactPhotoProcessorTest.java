package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ContactPhotoProcessorTest {

    private static byte[] png(int w, int h, boolean alpha) throws IOException {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(alpha ? new Color(10, 20, 30, 100) : Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void cropsToSquareAndScalesDown() throws IOException {
        ContactPhotoProcessor.Photo photo = new ContactPhotoProcessor(256).process(png(1200, 800, false));
        BufferedImage out = read(photo.bytes());
        assertThat(photo.mimeType()).isEqualTo("image/jpeg");
        assertThat(photo.extension()).isEqualTo("jpg");
        assertThat(out.getWidth()).isEqualTo(256);
        assertThat(out.getHeight()).isEqualTo(256);
        Color center = new Color(out.getRGB(128, 128));
        // Orange survives the trip through JPEG (no channel swap from the alpha input).
        assertThat(center.getRed()).isGreaterThan(220);
        assertThat(center.getGreen()).isBetween(170, 220);
        assertThat(center.getBlue()).isLessThan(40);
    }

    @Test
    void neverScalesUp() throws IOException {
        BufferedImage out =
                read(new ContactPhotoProcessor(512).process(png(120, 90, false)).bytes());
        assertThat(out.getWidth()).isEqualTo(90);
        assertThat(out.getHeight()).isEqualTo(90);
    }

    @Test
    void keepsTransparencyAsPng() throws IOException {
        ContactPhotoProcessor.Photo photo = new ContactPhotoProcessor(64).process(png(100, 100, true));
        assertThat(photo.mimeType()).isEqualTo("image/png");
        assertThat(read(photo.bytes()).getWidth()).isEqualTo(64);
    }

    @Test
    void refusesWhatIsNoPicture() {
        assertThatThrownBy(() -> new ContactPhotoProcessor(64).process("hello".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAHugeDeclaredSizeBeforeDecoding() {
        // 64 bytes on disk, 10 gigapixels declared — decoding would allocate ~40 GB.
        byte[] bomb = pngHeaderOnly(100_000, 100_000);

        assertThatThrownBy(() -> new ContactPhotoProcessor(64).process(bomb))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("megapixels");
    }

    @Test
    void acceptsADeclaredSizeBelowTheLimit() {
        ContactPhotoProcessor.checkDimensions(pngHeaderOnly(6000, 6000));
    }

    /** A PNG signature, an IHDR chunk with the given size and IEND — no pixel data. */
    private static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII));
        ihdr.putInt(width).putInt(height);
        ihdr.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        ByteBuffer out = ByteBuffer.allocate(8 + 4 + 17 + 4 + 12);
        out.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        out.putInt(13).put(ihdr.array()).putInt(crc(ihdr.array()));
        byte[] iend = "IEND".getBytes(StandardCharsets.US_ASCII);
        out.putInt(0).put(iend).putInt(crc(iend));
        return out.array();
    }

    private static int crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }
}
