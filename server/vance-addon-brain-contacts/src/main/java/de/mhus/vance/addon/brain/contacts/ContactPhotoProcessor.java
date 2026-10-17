package de.mhus.vance.addon.brain.contacts;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.nio.JpegWriter;
import com.sksamuel.scrimage.nio.PngWriter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns whatever picture arrives (upload, vCard {@code PHOTO}, Gravatar, a
 * project image) into the one form a contact keeps: a centred square of at
 * most {@code vance.contacts.photo-size} pixels. Always — a 12-megapixel
 * phone photo behind a 40-pixel avatar costs every list render.
 *
 * <p>Opaque pictures become JPEG, pictures with transparency stay PNG.
 * Smaller pictures are cropped to square but never scaled up.
 */
@Component
public class ContactPhotoProcessor {

    /** Upper bound for what is even decoded; the stored result is far smaller. */
    public static final int MAX_INPUT_BYTES = 15 * 1024 * 1024;

    private static final int JPEG_QUALITY = 85;

    private final int size;

    public ContactPhotoProcessor(@Value("${vance.contacts.photo-size:512}") int size) {
        this.size = Math.max(32, Math.min(size, 2048));
    }

    /** The processed picture, its MIME type and file extension. */
    public record Photo(byte[] bytes, String mimeType, String extension) {}

    public int size() {
        return size;
    }

    public Photo process(byte[] input) {
        if (input.length == 0) throw new IllegalArgumentException("The picture is empty");
        if (input.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException(
                    "The picture is larger than " + (MAX_INPUT_BYTES / (1024 * 1024)) + " MB");
        }
        ImmutableImage image;
        try {
            image = ImmutableImage.loader().detectOrientation(true).fromBytes(input);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Not a readable picture (JPEG, PNG, GIF or BMP)", e);
        }
        int side = Math.min(size, Math.min(image.width, image.height));
        ImmutableImage square = image.cover(side, side);
        try {
            if (translucent(square.awt())) {
                return new Photo(square.bytes(new PngWriter()), "image/png", "png");
            }
            return new Photo(square.bytes(new JpegWriter().withCompression(JPEG_QUALITY)), "image/jpeg", "jpg");
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the contact picture", e);
        }
    }

    /**
     * Whether any pixel is see-through. The decoder hands every PNG over with
     * an alpha channel, opaque or not — the channel says nothing, the pixels do.
     */
    static boolean translucent(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) return false;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0xFF) return true;
            }
        }
        return false;
    }
}
