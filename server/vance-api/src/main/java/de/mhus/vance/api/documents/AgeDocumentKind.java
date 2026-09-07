package de.mhus.vance.api.documents;

import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Contract vocabulary for age-encrypted documents — the marker values every
 * surface (REST, WebDAV, Web-UI, foot, LLM tools) agrees on. See
 * {@code planning/age-encryption.md}.
 *
 * <p>An age-encrypted document stores <b>armored ciphertext only</b>: the
 * PEM-like {@code -----BEGIN AGE ENCRYPTED FILE-----} block of the age v1
 * ASCII armor. The server never sees the plaintext — decryption and
 * encryption happen client-side (Web-UI, foot). Because the body cannot
 * declare a {@code kind} in front matter, the mime type is the carrier
 * instead: every save re-asserts {@code kind: age}.
 */
public final class AgeDocumentKind {

    /** Document kind of an age-encrypted document. */
    public static final String KIND = "age";

    /**
     * MIME type of an armored age document. Our own choice — nothing is
     * registered with IANA; the value only has to be stable and arrive
     * identically from upload, WebDAV and path-extension mapping.
     */
    public static final String MIME_TYPE = "application/age+armored";

    /** File extension, incl. the double-extension convention {@code foo.md.age}. */
    public static final String FILE_EXTENSION = "age";

    /** First line of the age v1 ASCII armor. */
    public static final String ARMOR_BEGIN = "-----BEGIN AGE ENCRYPTED FILE-----";

    /** Longest prefix worth inspecting for the armor begin line. */
    private static final int ARMOR_PROBE_LIMIT = 128;

    private AgeDocumentKind() {
    }

    /**
     * Whether a document with this {@code kind} / MIME pair is age-encrypted.
     * Both markers are accepted so a row is still recognised when only one
     * of them survived (e.g. an uploaded {@code .age} file whose kind was
     * never stamped).
     */
    public static boolean isAgeEncrypted(@Nullable String kind, @Nullable String mimeType) {
        if (kind != null && KIND.equals(kind.trim().toLowerCase())) return true;
        if (mimeType == null) return false;
        String mt = mimeType.toLowerCase().trim();
        int semi = mt.indexOf(';');
        if (semi >= 0) mt = mt.substring(0, semi).trim();
        return MIME_TYPE.equals(mt);
    }

    /**
     * Whether {@code path} carries the age extension — the double-extension
     * convention {@code bericht.md.age} also ends in {@code .age}.
     */
    public static boolean hasAgeExtension(@Nullable String path) {
        if (path == null) return false;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.toLowerCase().endsWith("." + FILE_EXTENSION);
    }

    /**
     * Whether {@code content} opens with the age armor begin line. A shape
     * check only — no crypto, no key material; it validates that a body
     * stored under {@code kind: age} is armored at all.
     */
    public static boolean looksArmored(@Nullable String content) {
        if (content == null) return false;
        String head = content.length() > ARMOR_PROBE_LIMIT
                ? content.substring(0, ARMOR_PROBE_LIMIT) : content;
        return head.trim().startsWith(ARMOR_BEGIN);
    }

    /** Byte-array variant of {@link #looksArmored(String)}. */
    public static boolean looksArmored(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        int limit = Math.min(bytes.length, ARMOR_PROBE_LIMIT);
        // Armor is pure ASCII — ISO-8859-1 decodes the probe 1:1 without
        // a charset error on arbitrary binary.
        return looksArmored(new String(bytes, 0, limit, StandardCharsets.ISO_8859_1));
    }
}
