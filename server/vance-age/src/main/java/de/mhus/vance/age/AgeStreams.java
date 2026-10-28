package de.mhus.vance.age;

import com.exceptionfactory.jagged.RecipientStanzaReader;
import com.exceptionfactory.jagged.RecipientStanzaWriter;
import com.exceptionfactory.jagged.UnsupportedRecipientStanzaException;
import com.exceptionfactory.jagged.framework.stream.StandardDecryptingChannelFactory;
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Binary age v1 as streams — for payloads that must not be held in memory whole
 * (files, command output), where {@link AgeCipher} works on armored strings.
 *
 * <p>age encrypts in 64 KiB chunks, each authenticated, with an end marker in the
 * last one: a stream cut short or tampered with fails while it is read, never
 * silently. X25519 identities only; passphrases are an interactive concern and
 * have no place on a machine-to-machine path.
 */
public final class AgeStreams {

    private AgeStreams() {}

    /**
     * Wraps {@code sink} so everything written is age-encrypted to every recipient.
     * <b>Closing the returned stream writes the final chunk and closes
     * {@code sink}</b> — without it the result cannot be decrypted.
     *
     * @throws AgeCryptoException on a malformed recipient or a header write failure
     */
    public static OutputStream encrypt(OutputStream sink, List<String> recipients) {
        Objects.requireNonNull(sink, "sink required");
        if (recipients == null || recipients.isEmpty()) {
            throw new AgeCryptoException("At least one recipient is required to encrypt");
        }
        List<RecipientStanzaWriter> writers = new ArrayList<>(recipients.size());
        for (String recipient : recipients) {
            try {
                writers.add(X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(recipient));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new AgeCryptoException("Not a well-formed age recipient: " + recipient, e);
            }
        }
        try {
            return Channels.newOutputStream(
                    new StandardEncryptingChannelFactory().newEncryptingChannel(Channels.newChannel(sink), writers));
        } catch (GeneralSecurityException | IOException e) {
            throw new AgeCryptoException("Failed to start the age stream", e);
        }
    }

    /**
     * Wraps {@code source} so reading yields the plaintext. The header is read and a
     * matching identity found before this returns; payload chunks are decrypted and
     * authenticated as they are read, so a corrupt or truncated payload surfaces as
     * an {@link IOException} from {@code read}.
     *
     * @throws AgeNoKeyMatchException no identity unwrapped the file key
     * @throws AgeCryptoException     the header is unreadable or corrupt
     */
    public static InputStream decrypt(InputStream source, List<String> identities) {
        Objects.requireNonNull(source, "source required");
        if (identities == null || identities.isEmpty()) {
            throw new AgeNoKeyMatchException("No age identity was provided");
        }
        List<RecipientStanzaReader> readers = new ArrayList<>(identities.size());
        for (String identity : identities) {
            try {
                readers.add(X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(identity));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new AgeCryptoException("Not a well-formed age identity: " + identity, e);
            }
        }
        try {
            return Channels.newInputStream(
                    new StandardDecryptingChannelFactory().newDecryptingChannel(Channels.newChannel(source), readers));
        } catch (UnsupportedRecipientStanzaException e) {
            throw new AgeNoKeyMatchException("None of the provided identities could decrypt the stream", e);
        } catch (GeneralSecurityException e) {
            // A wrong identity fails the X25519 unwrap — the same answer as an unmatched stanza.
            throw new AgeNoKeyMatchException("None of the provided identities could decrypt the stream", e);
        } catch (IOException e) {
            throw new AgeCryptoException("Failed to read the age stream header", e);
        }
    }
}
