package de.mhus.vance.toolpack.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 content hashes for the file-tool If-Match guard.
 *
 * <p>{@code file_read}, {@code file_edit} and {@code file_write} return a
 * {@code contentHash} of the file's raw UTF-8 bytes, and the edit/write
 * tools accept it back as {@code expectedContentHash}: when the file
 * changed since the caller last read it, the change is refused instead
 * of silently applied to stale expectations. The hash always covers the
 * <em>whole file</em>, independent of any window or character cap the
 * read applied — it identifies the file's state, not the served view.
 *
 * <p>Both consumers of this protocol (foot's {@code client_file_*} tools
 * and brain's {@code work_file_*} tools) sit on this module, so the
 * format is defined once even though a hash is never compared across
 * targets — each tool pair computes and verifies within its own backend.
 */
public final class ContentHashes {

    private static final int STREAM_BUFFER = 8192;

    private ContentHashes() {
        // Static utility.
    }

    /**
     * SHA-256 of the text's UTF-8 bytes as lowercase hex. Equivalent to
     * {@link #sha256Hex(Path)} for a file whose bytes are that encoding.
     */
    public static String sha256Hex(String text) {
        return hashOf(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 of the file's raw bytes as lowercase hex, streamed so that
     * a multi-gigabyte file never has to be held in memory to be hashed.
     *
     * @throws UncheckedIOException when the file cannot be read
     */
    public static String sha256Hex(Path file) {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[STREAM_BUFFER];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                md.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Hash failed: " + e.getMessage(), e);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Short form for error messages — enough of the hash to correlate
     * two values without flooding the model with 128 hex characters.
     */
    public static String abbreviate(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String hashOf(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256 — this branch is for completeness.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
