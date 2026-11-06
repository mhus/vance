package de.mhus.vance.brain.ursaeventtrigger;

import de.mhus.vance.shared.ursaevents.WebhookSignatureSpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Checks the HMAC a provider put on a webhook (see {@link WebhookSignatureSpec}).
 *
 * <p>Pure: it gets the secret, the raw body and a way to read headers, and answers with the reason a request is
 * refused or nothing when it is fine. The body must be the bytes that were received — a body parsed and written
 * out again no longer matches the signature. The comparison is constant-time.
 */
final class WebhookSignatureVerifier {

    private static final String HMAC = "HmacSHA256";

    private WebhookSignatureVerifier() {}

    /**
     * @param header reads a request header (case-insensitive), {@code null} when absent
     * @return empty when the signature holds, otherwise why it does not — for the log, never for the caller
     */
    static Optional<String> refusal(
            WebhookSignatureSpec spec,
            String secret,
            byte[] body,
            Function<String, @Nullable String> header,
            Instant now) {
        String provided = header.apply(spec.header());
        if (provided == null || provided.isBlank()) {
            return Optional.of("no '" + spec.header() + "' header");
        }
        provided = provided.trim();
        if (spec.prefix() != null) {
            if (!provided.startsWith(spec.prefix())) {
                return Optional.of("signature does not start with '" + spec.prefix() + "'");
            }
            provided = provided.substring(spec.prefix().length());
        }
        byte[] expected;
        try {
            expected = switch (spec.encoding()) {
                case HEX -> HexFormat.of().parseHex(provided);
                case BASE64 -> Base64.getDecoder().decode(provided);
            };
        } catch (IllegalArgumentException e) {
            return Optional.of(
                    "signature is not valid " + spec.encoding().name().toLowerCase());
        }

        byte[] signed = body;
        if (spec.timestampHeader() != null) {
            String raw = header.apply(spec.timestampHeader());
            long timestamp;
            try {
                timestamp = Long.parseLong(raw == null ? "" : raw.trim());
            } catch (NumberFormatException e) {
                return Optional.of("no usable '" + spec.timestampHeader() + "' header");
            }
            if (Math.abs(now.getEpochSecond() - timestamp) > spec.toleranceSeconds()) {
                return Optional.of("timestamp is more than " + spec.toleranceSeconds() + " s from now");
            }
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            signed = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, signed, 0, prefix.length);
            System.arraycopy(body, 0, signed, prefix.length, body.length);
        }

        byte[] computed;
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            computed = mac.doFinal(signed);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("cannot compute " + HMAC, e);
        }
        return MessageDigest.isEqual(computed, expected) ? Optional.empty() : Optional.of("signature does not match");
    }
}
