package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
}
