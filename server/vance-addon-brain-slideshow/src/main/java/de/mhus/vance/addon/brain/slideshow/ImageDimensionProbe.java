package de.mhus.vance.addon.brain.slideshow;

import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Probes an image's pixel dimensions without decoding the pixel
 * matrix. Uses {@code ImageIO} reader streams — only the header is
 * touched, so it's cheap even on large JPEGs.
 *
 * <p>Returns {@code null} for formats we can't probe ({@code image/svg+xml}
 * has no fixed pixel size; unknown formats simply fail). Callers
 * should treat {@code null} as "unknown" — the UI falls back to a
 * default aspect ratio.
 */
@Slf4j
public final class ImageDimensionProbe {

    public record Dim(int width, int height) { }

    private ImageDimensionProbe() {
        // utility class
    }

    public static @Nullable Dim probe(InputStream in, String mimeType) {
        if (mimeType == null) return null;
        if ("image/svg+xml".equals(mimeType)) return null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) return null;
            var readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                Dimension d = new Dimension(reader.getWidth(0), reader.getHeight(0));
                return new Dim(d.width, d.height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            log.debug("ImageDimensionProbe failed for mime={}: {}", mimeType, e.getMessage());
            return null;
        }
    }
}
