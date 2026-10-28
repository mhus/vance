package de.mhus.vance.runner;

import de.mhus.vance.age.AgeKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

/**
 * The runner's age key pair. The identity (secret) opens requests; the recipient
 * (public) is what the operator registers with the brain. Stored in the format of
 * {@code age-keygen}, so the file doubles as the way to read the recipient:
 * <pre>
 * # created: …
 * # public key: age1…
 * AGE-SECRET-KEY-1…
 * </pre>
 * Created on first start with owner-only permissions; a Secret mounted at the same
 * path works as well.
 */
public record RunnerIdentity(String identity, String recipient) {

    /** Reads the key file, creating it when missing. */
    public static RunnerIdentity loadOrCreate(Path file) throws IOException {
        if (!Files.exists(file)) {
            create(file);
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String identity = AgeKeys.extractIdentity(text);
        String recipient = AgeKeys.extractRecipient(text);
        if (identity == null || recipient == null) {
            throw new IOException(
                    file + " has no age identity and '# public key:' line — expected the age-keygen format");
        }
        return new RunnerIdentity(identity, recipient);
    }

    private static void create(Path file) throws IOException {
        AgeKeys.AgeKeyPair pair = AgeKeys.generateKeyPair();
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String content =
                "# created: " + Instant.now() + "\n# public key: " + pair.recipient() + "\n" + pair.identity() + "\n";
        // Owner-only from the first byte: create the file with the mode, then write.
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "RunnerIdentity[recipient=" + recipient + "]"; // never the secret
    }
}
