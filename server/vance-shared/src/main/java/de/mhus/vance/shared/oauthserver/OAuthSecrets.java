package de.mhus.vance.shared.oauthserver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random values and their stored form for the OAuth server.
 *
 * <p>Authorization codes and refresh tokens are bearer secrets. The database
 * only ever holds their SHA-256 — a dump of the collections is then a list of
 * hashes, not a list of working credentials. A plain hash is enough: the
 * inputs are 256 random bits, there is nothing to brute-force.
 */
public final class OAuthSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuthSecrets() {}

    /** {@code bytes} random bytes, Base64URL without padding. */
    public static String random(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Hex SHA-256 of {@code value} — the stored form of a code or refresh token. */
    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Constant-time comparison of two stored hashes. The hashes are not
     * secret, but comparing them in constant time costs nothing and keeps the
     * question from coming up.
     */
    public static boolean sameHash(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
