package de.mhus.vance.runner.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tenant's public JWT keys, from a file of PEM {@code PUBLIC KEY} blocks (several
 * during a key rotation). Public keys only — the runner can check tokens but never
 * issue one. The format is shared with the admin shell, which exports them (<code>runner keys</code>).
 */
public final class TenantKeys {

    private static final Pattern BLOCK =
            Pattern.compile("-----BEGIN PUBLIC KEY-----([A-Za-z0-9+/=\\s]+?)-----END PUBLIC KEY-----");

    private TenantKeys() {}

    public static List<PublicKey> load(Path file) throws IOException {
        List<PublicKey> keys = new ArrayList<>();
        Matcher matcher = BLOCK.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (matcher.find()) {
            try {
                byte[] der = Base64.getMimeDecoder().decode(matcher.group(1));
                keys.add(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der)));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IOException("Not a usable EC public key in " + file + ": " + e.getMessage(), e);
            }
        }
        if (keys.isEmpty()) {
            throw new IOException("No PEM public key found in " + file);
        }
        return List.copyOf(keys);
    }

    /** PEM form of a public key, as {@link #load} reads it. */
    public static String toPem(PublicKey key) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }
}
