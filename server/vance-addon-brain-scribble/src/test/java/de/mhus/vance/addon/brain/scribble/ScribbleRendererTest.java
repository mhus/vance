package de.mhus.vance.addon.brain.scribble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ScribbleRendererTest {

    private static ScribbleSheet sheetWithInk() {
        ScribbleStroke stroke = new ScribbleStroke(
                ScribbleStroke.Tool.PEN,
                "2",
                ScribbleStroke.Width.L,
                List.of(new ScribblePoint(100, 100, 0.5), new ScribblePoint(400, 300, 0.9)));
        return new ScribbleSheet("T", ScribbleSize.a4Portrait(), List.of(stroke));
    }

    private static ScribbleSheet blankSheet() {
        return new ScribbleSheet("T", ScribbleSize.a4Portrait(), List.of());
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void rendersNativeA4Png() throws IOException {
        byte[] png = ScribbleRenderer.renderPng(sheetWithInk(), 150, null);
        assertThat(png.length).isGreaterThan(3);
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        BufferedImage img = decode(png);
        assertThat(img.getWidth()).isEqualTo(1240);
        assertThat(img.getHeight()).isEqualTo(1754);
    }

    @Test
    void dpiScalesTheRaster() throws IOException {
        BufferedImage img = decode(ScribbleRenderer.renderPng(sheetWithInk(), 300, null));
        assertThat(img.getWidth()).isEqualTo(2480);
        assertThat(img.getHeight()).isEqualTo(3508);
    }

    @Test
    void inkIsVisibleOnWhiteGround() throws IOException {
        BufferedImage img = decode(ScribbleRenderer.renderPng(sheetWithInk(), 150, null));
        assertThat(pixel(img, 100, 100)).isNotEqualTo(0xFFFFFF);
        assertThat(pixel(img, 250, 200)).isNotEqualTo(0xFFFFFF);
        // far corner stays the white ground
        assertThat(pixel(img, 1200, 1700)).isEqualTo(0xFFFFFF);
    }

    @Test
    void colorComesFromThePalette() throws IOException {
        BufferedImage img = decode(ScribbleRenderer.renderPng(sheetWithInk(), 150, null));
        int rgb = pixel(img, 100, 100);
        // stroke color "2" is #dc2626 — anti-aliasing blends, so the red
        // component must dominate while blue/green stay low.
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        assertThat(r).isGreaterThan(120);
        assertThat(g).isLessThan(120);
        assertThat(b).isLessThan(120);
    }

    @Test
    void regionCrops() throws IOException {
        BufferedImage img = decode(
                ScribbleRenderer.renderPng(sheetWithInk(), 150, new ScribbleRenderer.Region(600, 800, 400, 500)));
        assertThat(img.getWidth()).isEqualTo(400);
        assertThat(img.getHeight()).isEqualTo(500);
    }

    @Test
    void regionIsClampedToTheSheet() throws IOException {
        BufferedImage img = decode(
                ScribbleRenderer.renderPng(sheetWithInk(), 150, new ScribbleRenderer.Region(-100, -100, 5000, 5000)));
        assertThat(img.getWidth()).isEqualTo(1240);
        assertThat(img.getHeight()).isEqualTo(1754);
    }

    @Test
    void regionOutsideTheSheetThrows() {
        assertThatThrownBy(() -> ScribbleRenderer.renderPng(
                        sheetWithInk(), 150, new ScribbleRenderer.Region(-500, -500, 100, 100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankSheetRendersAllWhite() throws IOException {
        BufferedImage img = decode(ScribbleRenderer.renderPng(blankSheet(), 150, null));
        assertThat(pixel(img, 10, 10)).isEqualTo(0xFFFFFF);
        assertThat(pixel(img, 1200, 1700)).isEqualTo(0xFFFFFF);
    }

    @Test
    void nonPositiveDpiThrows() {
        assertThatThrownBy(() -> ScribbleRenderer.renderPng(sheetWithInk(), 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int pixel(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFFFFFF;
    }
}
