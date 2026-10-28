package de.mhus.vance.runner.protocol;

import de.mhus.vance.age.AgeCryptoException;
import de.mhus.vance.age.AgeKeys;
import de.mhus.vance.age.AgeNoKeyMatchException;
import de.mhus.vance.age.AgeStreams;
import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The age envelope around every brain ↔ runner call ({@code planning/runner.md} §4).
 *
 * <p><b>Request.</b> The brain encrypts to the runner's registered recipient. Inside:
 * a {@link RunnerRequest} header with the runner-bound JWT, the operation and a
 * freshly generated one-time <em>reply key</em>, then the body. Only the process
 * holding the runner's identity can read any of it — a redirected address yields
 * ciphertext and nothing else.
 *
 * <p><b>Response.</b> The runner encrypts to that reply key. Whoever can decrypt it
 * holds the request's plaintext, i.e. is the runner; the age authentication makes the
 * answer tamper-evident, and the request's {@code jti} inside binds it to this one
 * call. The reply key lives in the {@link ReplyHandle} and nowhere else, so an old
 * answer cannot be replayed against a new request.
 *
 * <p>Both directions stream: the body is never held whole, and a truncated or altered
 * stream fails while it is read. The caller must therefore read the body to its end
 * before trusting it.
 */
public final class RunnerEnvelope {

    /** The protocol version this build speaks. */
    public static final int VERSION = 1;

    private RunnerEnvelope() {}

    // ───────────────────── brain side ─────────────────────

    /**
     * Writes an encrypted request to {@code out}. <b>Closes {@code out}</b> (the age
     * stream needs its final chunk).
     *
     * @param runnerRecipient the runner's registered age recipient
     * @param token           the runner-bound JWT
     * @param jti             that token's {@code jti} — the answer must carry it
     * @param args            any Jackson-serialisable arguments of {@code op}; may be {@code null}
     * @param body            the payload, or {@code null}
     * @return what is needed to read the answer
     */
    public static ReplyHandle sealRequest(
            String runnerRecipient,
            String token,
            String jti,
            String op,
            @Nullable Object args,
            @Nullable InputStream body,
            OutputStream out) {
        requireText(token, "token");
        requireText(jti, "jti");
        requireText(op, "op");
        AgeKeys.AgeKeyPair reply = AgeKeys.generateKeyPair();
        RunnerRequest header = new RunnerRequest(
                VERSION, token, reply.recipient(), op, args == null ? null : Frames.MAPPER.valueToTree(args));
        try (OutputStream sealed = encryptTo(runnerRecipient, out)) {
            Frames.writeHeader(sealed, header);
            if (body != null) {
                body.transferTo(sealed);
            }
        } catch (IOException e) {
            throw new RunnerProtocolException(Kind.MALFORMED, "Cannot write the request: " + e.getMessage(), e);
        }
        return new ReplyHandle(reply.identity(), jti);
    }

    /** In-memory form of {@link #sealRequest(String, String, String, String, Object, InputStream, OutputStream)}. */
    public static SealedRequest sealRequest(
            String runnerRecipient, String token, String jti, String op, @Nullable Object args, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReplyHandle reply = sealRequest(runnerRecipient, token, jti, op, args, new ByteArrayInputStream(body), out);
        return new SealedRequest(out.toByteArray(), reply);
    }

    /** An encrypted request and the key to read its answer with. */
    public record SealedRequest(byte[] bytes, ReplyHandle reply) {}

    /**
     * What the brain keeps between sending a request and reading its answer: the
     * one-time identity and the {@code jti} to expect. Not serialisable on purpose;
     * the identity never leaves this object.
     */
    public static final class ReplyHandle {

        private final String identity;
        private final String jti;

        private ReplyHandle(String identity, String jti) {
            this.identity = identity;
            this.jti = jti;
        }

        /**
         * Reads the answer: decrypts with the one-time identity, then checks it names
         * this request's {@code jti}.
         *
         * @throws RunnerProtocolException {@link Kind#WRONG_REPLY} when the answer is not
         *     encrypted to this request's key or answers another call
         */
        public OpenedResponse openResponse(InputStream in) {
            InputStream plain;
            try {
                plain = AgeStreams.decrypt(in, List.of(identity));
            } catch (AgeNoKeyMatchException e) {
                throw new RunnerProtocolException(Kind.WRONG_REPLY, "The answer is not addressed to this request", e);
            } catch (AgeCryptoException e) {
                throw new RunnerProtocolException(Kind.MALFORMED, "The answer is not a readable age file", e);
            }
            RunnerResponse header = Frames.readHeader(plain, RunnerResponse.class);
            if (header.v() != VERSION) {
                throw new RunnerProtocolException(
                        Kind.UNSUPPORTED_VERSION, "Unsupported protocol version " + header.v());
            }
            if (!jti.equals(header.jti())) {
                throw new RunnerProtocolException(Kind.WRONG_REPLY, "The answer belongs to another request");
            }
            return new OpenedResponse(header, plain);
        }

        /** In-memory form of {@link #openResponse(InputStream)}. */
        public OpenedBytes<RunnerResponse> openResponse(byte[] bytes) {
            OpenedResponse opened = openResponse(new ByteArrayInputStream(bytes));
            return new OpenedBytes<>(opened.header(), readAll(opened.body()));
        }

        @Override
        public String toString() {
            return "ReplyHandle[jti=" + jti + "]"; // never the identity
        }
    }

    /** A decrypted answer: the header, and the body still to be read (to its end). */
    public record OpenedResponse(RunnerResponse header, InputStream body) {}

    // ───────────────────── runner side ─────────────────────

    /**
     * Opens a request with the runner's identity.
     *
     * @throws RunnerProtocolException {@link Kind#NOT_FOR_US} when the bytes are not an
     *     age file for this identity; {@link Kind#MALFORMED} / {@link Kind#UNSUPPORTED_VERSION}
     *     for a bad header
     */
    public static OpenedRequest openRequest(String runnerIdentity, InputStream in) {
        InputStream plain;
        try {
            plain = AgeStreams.decrypt(in, List.of(runnerIdentity));
        } catch (AgeNoKeyMatchException e) {
            throw new RunnerProtocolException(Kind.NOT_FOR_US, "Not encrypted to this runner", e);
        } catch (AgeCryptoException e) {
            throw new RunnerProtocolException(Kind.NOT_FOR_US, "Not a readable age file", e);
        }
        RunnerRequest header = Frames.readHeader(plain, RunnerRequest.class);
        if (header.v() != VERSION) {
            throw new RunnerProtocolException(Kind.UNSUPPORTED_VERSION, "Unsupported protocol version " + header.v());
        }
        requireField(header.token(), "token");
        requireField(header.op(), "op");
        if (header.replyKey() == null || !AgeKeys.isRecipient(header.replyKey())) {
            throw new RunnerProtocolException(Kind.MALFORMED, "The reply key is not an age recipient");
        }
        return new OpenedRequest(header, plain);
    }

    /** In-memory form of {@link #openRequest(String, InputStream)}. */
    public static OpenedBytes<RunnerRequest> openRequest(String runnerIdentity, byte[] bytes) {
        OpenedRequest opened = openRequest(runnerIdentity, new ByteArrayInputStream(bytes));
        return new OpenedBytes<>(opened.header(), readAll(opened.body()));
    }

    /**
     * Writes the answer, encrypted to the request's reply key. <b>Closes {@code out}.</b>
     */
    public static void sealResponse(
            String replyKey, RunnerResponse header, @Nullable InputStream body, OutputStream out) {
        try (OutputStream sealed = encryptTo(replyKey, out)) {
            Frames.writeHeader(sealed, header);
            if (body != null) {
                body.transferTo(sealed);
            }
        } catch (IOException e) {
            throw new RunnerProtocolException(Kind.MALFORMED, "Cannot write the answer: " + e.getMessage(), e);
        }
    }

    /** In-memory form of {@link #sealResponse(String, RunnerResponse, InputStream, OutputStream)}. */
    public static byte[] sealResponse(String replyKey, RunnerResponse header, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sealResponse(replyKey, header, new ByteArrayInputStream(body), out);
        return out.toByteArray();
    }

    /** A decrypted request: the header, and the body still to be read (to its end). */
    public record OpenedRequest(RunnerRequest header, InputStream body) {}

    /** A header with its body read completely into memory. */
    public record OpenedBytes<H>(H header, byte[] body) {}

    // ───────────────────── internals ─────────────────────

    private static OutputStream encryptTo(String recipient, OutputStream out) {
        try {
            return AgeStreams.encrypt(out, List.of(recipient));
        } catch (AgeCryptoException e) {
            throw new RunnerProtocolException(Kind.MALFORMED, "Cannot encrypt to " + recipient, e);
        }
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            // A truncated or altered stream: age reports it while reading.
            throw new RunnerProtocolException(Kind.MALFORMED, "The body is corrupt: " + e.getMessage(), e);
        }
    }

    private static void requireText(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requireField(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new RunnerProtocolException(Kind.MALFORMED, "The request has no " + name);
        }
    }
}
