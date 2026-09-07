package de.mhus.vance.age;

import com.exceptionfactory.jagged.x25519.X25519KeyPairGenerator;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identity and recipient handling — the Java twin of the key helpers in
 * {@code @vance/age}. Strictly the standard v1 shapes: the post-quantum
 * variants jagged could handle are deliberately rejected (they would break
 * interop with the reference {@code age} CLI).
 */
public final class AgeKeys {

    /** Bech32 data charset (lower case) — excludes {@code 1}, {@code b}, {@code i}, {@code o}. */
    private static final String BECH32_LOWER = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    private static final String BECH32_UPPER = BECH32_LOWER.toUpperCase();

    /** {@code AGE-SECRET-KEY-1} + 52 data chars + 6 checksum = 74. */
    private static final Pattern IDENTITY = Pattern.compile(
            "^AGE-SECRET-KEY-1[" + BECH32_UPPER + "]{58}$");

    /** {@code age1} + 52 data chars + 6 checksum = 62. */
    private static final Pattern RECIPIENT = Pattern.compile(
            "^age1[" + BECH32_LOWER + "]{58}$");

    /** Unanchored searches for extracting keys out of whole keygen / recipients files. */
    private static final Pattern IDENTITY_IN_TEXT = Pattern.compile(
            "AGE-SECRET-KEY-1[" + BECH32_UPPER + "]{58}");

    private static final Pattern RECIPIENT_IN_TEXT = Pattern.compile(
            "age1[" + BECH32_LOWER + "]{58}");

    private static final Pattern ALL_IDENTITIES_IN_TEXT = Pattern.compile(
            "AGE-SECRET-KEY-1[" + BECH32_UPPER + "]{58}");

    private AgeKeys() {
    }

    /** Whether {@code value} is a well-formed X25519 secret key ({@code AGE-SECRET-KEY-1…}). */
    public static boolean isIdentity(String value) {
        return value != null && IDENTITY.matcher(value.trim()).matches();
    }

    /** Whether {@code value} is a well-formed X25519 recipient ({@code age1…}). */
    public static boolean isRecipient(String value) {
        return value != null && RECIPIENT.matcher(value.trim()).matches();
    }

    /**
     * The first {@code AGE-SECRET-KEY-1} key in pasted or imported text.
     * Keygen files carry {@code # public key: age1…} comment lines above
     * the secret — extraction happens here so no call site has to care.
     */
    public static String extractIdentity(String text) {
        return find(IDENTITY_IN_TEXT, text);
    }

    /** The first {@code age1…} recipient in pasted or imported text (keygen public-key line, recipients file, bare key). */
    public static String extractRecipient(String text) {
        return find(RECIPIENT_IN_TEXT, text);
    }

    /**
     * Every {@code AGE-SECRET-KEY-1} key in pasted or imported text — an
     * identity file may carry several keys ({@code age -i} reads them all).
     */
    public static List<String> extractIdentities(String text) {
        List<String> identities = new ArrayList<>();
        if (text != null) {
            Matcher matcher = ALL_IDENTITIES_IN_TEXT.matcher(text);
            while (matcher.find() && !identities.contains(matcher.group())) {
                identities.add(matcher.group());
            }
        }
        return List.copyOf(identities);
    }

    /** A freshly generated X25519 identity pair. */
    public static AgeKeyPair generateKeyPair() {
        KeyPair keyPair;
        try {
            keyPair = new X25519KeyPairGenerator().generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AgeCryptoException("X25519 key generation is not available", e);
        }
        String identity = keyPair.getPrivate().toString();
        String recipient = keyPair.getPublic().toString();
        if (!isIdentity(identity) || !isRecipient(recipient)) {
            throw new AgeCryptoException("Generated key pair is not Bech32-encoded as expected");
        }
        return new AgeKeyPair(identity, recipient);
    }

    private static String find(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /** An X25519 identity and its recipient — the pair {@link #generateKeyPair()} returns. */
    public record AgeKeyPair(String identity, String recipient) {
    }
}
