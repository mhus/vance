package de.mhus.vance.age;

import com.exceptionfactory.jagged.RecipientStanzaReader;
import com.exceptionfactory.jagged.RecipientStanzaWriter;
import com.exceptionfactory.jagged.UnsupportedRecipientStanzaException;
import com.exceptionfactory.jagged.framework.armor.ArmoredDecryptingChannelFactory;
import com.exceptionfactory.jagged.framework.armor.ArmoredDecodingException;
import com.exceptionfactory.jagged.framework.armor.ArmoredEncryptingChannelFactory;
import com.exceptionfactory.jagged.scrypt.ScryptRecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.scrypt.ScryptRecipientStanzaWriterFactory;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The age facade for every Java consumer — the twin of {@code AgeCipher}'s
 * TypeScript counterpart in {@code @vance/age}. Wraps jagged (pure JCA)
 * with the byte-array-in / armored-string-out shape Vance's document flow
 * needs, so neither foot's view decryption nor a brain tool touches NIO
 * channels or stanza plumbing.
 *
 * <p>Scope is standard age v1 only: X25519 recipients and passphrases,
 * armored output. The scrypt work factor matches the other implementations'
 * default (18), so files written here behave like files written by the
 * reference {@code age} CLI or typage.
 */
public final class AgeCipher {

    /**
     * Base-2 logarithm of the scrypt work factor for passphrase encryption —
     * the same default typage and (measured on this class of hardware) the
     * Go reference CLI pick.
     */
    public static final int DEFAULT_SCRYPT_WORK_FACTOR = 18;

    private static final int CHUNK_SIZE = 64 * 1024;

    private AgeCipher() {
    }

    /**
     * Encrypt UTF-8 plaintext to one or more X25519 recipients, armored
     * output. Every listed recipient will be able to decrypt.
     *
     * @throws AgeCryptoException on malformed recipients or encryption failures
     */
    public static String encryptArmored(String plaintext, List<String> recipients) {
        Objects.requireNonNull(plaintext, "plaintext required");
        if (recipients == null || recipients.isEmpty()) {
            throw new AgeCryptoException("At least one recipient is required to encrypt");
        }
        List<RecipientStanzaWriter> writers = new ArrayList<>(recipients.size());
        for (String recipient : recipients) {
            try {
                writers.add(X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(recipient));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // jagged's Bech32 decoder reports malformed keys as unchecked
                // IllegalArgumentException — both shapes mean "not a key".
                throw new AgeCryptoException(
                        "Not a well-formed age recipient: " + recipient, e);
            }
        }
        return doEncryptArmored(plaintext, writers);
    }

    /**
     * Encrypt UTF-8 plaintext with a passphrase, armored output. Every
     * encryption runs the scrypt key derivation (work factor 18, ~seconds) —
     * editing sessions should prefer identities, see
     * {@code planning/age-encryption.md} §1.4.
     */
    public static String encryptArmoredWithPassphrase(String plaintext, String passphrase) {
        Objects.requireNonNull(plaintext, "plaintext required");
        Objects.requireNonNull(passphrase, "passphrase required");
        if (passphrase.isEmpty()) {
            throw new AgeCryptoException("A passphrase is required to encrypt");
        }
        RecipientStanzaWriter writer = ScryptRecipientStanzaWriterFactory
                .newRecipientStanzaWriter(
                        passphrase.getBytes(StandardCharsets.UTF_8),
                        DEFAULT_SCRYPT_WORK_FACTOR);
        return doEncryptArmored(plaintext, List.of(writer));
    }

    /**
     * Decrypt an armored document with every provided secret tried in
     * sequence by jagged's payload key reader (identities and passphrases
     * alike). Returns the UTF-8 plaintext.
     *
     * @throws AgeArmorException      the text is not decodable armor
     * @throws AgeNoKeyMatchException  no provided secret unwrapped the file key
     * @throws AgeCryptoException     header or payload failures (corrupt file)
     */
    public static String decryptArmored(String armored, AgeSecrets secrets) {
        Objects.requireNonNull(armored, "armored text required");
        Objects.requireNonNull(secrets, "secrets required");
        if (secrets.isEmpty()) {
            throw new AgeNoKeyMatchException("No age identity or passphrase was provided");
        }
        List<RecipientStanzaReader> readers = new ArrayList<>();
        for (String identity : secrets.identities()) {
            try {
                readers.add(X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(identity));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new AgeCryptoException(
                        "Not a well-formed age identity: " + identity, e);
            }
        }
        for (String passphrase : secrets.passphrases()) {
            if (passphrase == null || passphrase.isEmpty()) {
                continue;
            }
            try {
                readers.add(ScryptRecipientStanzaReaderFactory.newRecipientStanzaReader(
                        passphrase.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new AgeCryptoException("Failed to prepare passphrase secret", e);
            }
        }

        ReadableByteChannel input = Channels.newChannel(
                new ByteArrayInputStream(armored.getBytes(StandardCharsets.UTF_8)));
        ReadableByteChannel decrypting;
        try {
            decrypting = new ArmoredDecryptingChannelFactory()
                    .newDecryptingChannel(input, readers);
        } catch (ArmoredDecodingException e) {
            throw new AgeArmorException("Not a decodable age ASCII armor document", e);
        } catch (UnsupportedRecipientStanzaException e) {
            throw new AgeNoKeyMatchException(
                    "None of the provided identities or passphrases could decrypt the document", e);
        } catch (GeneralSecurityException e) {
            // Key derivation territory: a wrong passphrase fails the scrypt
            // unwrap, a wrong identity fails the X25519 unwrap — the same
            // answer for the user as an unmatched stanza.
            throw new AgeNoKeyMatchException(
                    "None of the provided identities or passphrases could decrypt the document", e);
        } catch (IOException e) {
            throw new AgeCryptoException("Failed to read the age document header", e);
        }

        try (decrypting) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
            while (decrypting.read(buffer) != -1) {
                buffer.flip();
                out.write(buffer.array(), 0, buffer.limit());
                buffer.clear();
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgeCryptoException("Failed to decrypt the age document payload", e);
        }
    }

    private static String doEncryptArmored(String plaintext, List<RecipientStanzaWriter> writers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(out);
        try (WritableByteChannel encrypting = new ArmoredEncryptingChannelFactory()
                .newEncryptingChannel(target, writers)) {
            ByteBuffer buffer = ByteBuffer.wrap(plaintext.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                encrypting.write(buffer);
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new AgeCryptoException("Failed to encrypt the document", e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
