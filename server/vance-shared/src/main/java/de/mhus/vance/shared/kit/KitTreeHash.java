package de.mhus.vance.shared.kit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One hash over a whole kit directory, computed the same way on both
 * ends so a signature made at delivery still verifies after transport.
 *
 * <p>The recipe is deliberately boring: every file's path and content
 * hash, sorted by path, concatenated, hashed again.
 *
 * <pre>
 *   treeHash = sha256( for each file, sorted by path:
 *                        "&lt;path&gt;\0&lt;sha256(content)&gt;\n" )
 * </pre>
 *
 * <p>What it is <em>not</em> is as important as what it is. It is not a
 * hash of an archive: packing order, timestamps, permissions and
 * compression all differ between the machine that signs and the one that
 * verifies, and any of them would break a signature without anything
 * being wrong. Sorting by path removes the only remaining source of
 * order, and hashing content per file means the result also tells you
 * <em>which</em> file differs when it does.
 *
 * <p>Directories are not hashed. An empty directory carries no kit
 * content, and Git does not preserve one either — signing over something
 * the transport drops would fail verification for a difference nobody
 * can see.
 */
public final class KitTreeHash {

    /**
     * Detached signature file, excluded from the hash it accompanies.
     * Declared here rather than on the verifier because both ends of a
     * delivery have to agree on it, and only one of them verifies.
     */
    public static final String SIGNATURE_FILENAME = "kit.sig.yaml";

    private KitTreeHash() {}

    /** {@code sha256:<hex>} over the whole directory. */
    public static String of(Path root) {
        StringBuilder canonical = new StringBuilder();
        for (String line : canonicalLines(root)) {
            canonical.append(line);
        }
        return KitHash.of(canonical.toString());
    }

    /**
     * The per-file lines the tree hash is built from, in order. Exposed
     * so a mismatch can be explained — "the signature does not match" is
     * a dead end, "this file changed" is not.
     */
    static List<String> canonicalLines(Path root) {
        List<String> lines = new ArrayList<>();
        for (Path file : KitTree.walkNoSymlinks(root)) {
            if (!Files.isRegularFile(file)) continue;
            String rel = root.relativize(file).toString().replace('\\', '/');
            // The signature file cannot be part of what it signs.
            if (rel.equals(SIGNATURE_FILENAME)) continue;
            // Nor can the repository metadata. A kit cloned from git carries
            // .git when it is addressed at the repo root, and its contents —
            // packfiles, index, refs — differ between any two clones. Hashing
            // them would make a signature over a git source unverifiable by
            // construction.
            if (rel.equals(".git") || rel.startsWith(".git/")) continue;
            lines.add(rel + "\0" + sha256Hex(file) + "\n");
        }
        Collections.sort(lines);
        return lines;
    }

    private static String sha256Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (IOException e) {
            throw new KitException("failed to read " + file + " while hashing the kit", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * The bytes a signature actually covers: the tree hash plus the
     * purchase claims from the descriptor.
     *
     * <p>Signing the tree alone would leave {@code licensedTo} editable
     * without breaking anything — the tenant binding would then be a
     * suggestion. Everything the delivery asserts has to be inside the
     * signature or it asserts nothing.
     */
    public static byte[] signedPayload(
            String treeHash, String licensedTo, String purchaseId, String licenseExpiresAt) {
        String payload = String.join("\n",
                "vancetope-kit-signature/1",
                "tree=" + treeHash,
                "licensedTo=" + licensedTo,
                "purchaseId=" + purchaseId,
                "licenseExpiresAt=" + licenseExpiresAt);
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}
