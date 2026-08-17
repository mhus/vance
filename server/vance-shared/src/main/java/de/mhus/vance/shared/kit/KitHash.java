package de.mhus.vance.shared.kit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content hashes recorded per installed artefact.
 *
 * <p>They answer exactly one question — <i>did the user touch this since
 * the install?</i> — and carry no security weight: a kit that could
 * rewrite the record could rewrite the hashes with it, which is why the
 * record path itself is guarded instead
 * ({@link KitRecordStore#isReservedPath}).
 */
public final class KitHash {

    private static final String PREFIX = "sha256:";

    private KitHash() {}

    /** {@code sha256:<hex>} over the UTF-8 bytes of the given content. */
    public static String of(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(PREFIX.length() + bytes.length * 2);
            hex.append(PREFIX);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
