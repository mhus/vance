package de.mhus.vance.runner.protocol;

import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Verifies the runner-bound JWT the brain puts into every request
 * ({@code planning/runner.md} §4.4). Only the tenant's <em>public</em> key is needed,
 * so the runner can check tokens but never issue one.
 *
 * <p>The JWS is checked by hand with the JDK ({@code ES256}, as the brain's
 * {@code JwtService} signs) rather than by a JWT library: it keeps the runner small
 * and buildable as a native image, and the rules are few and fixed. What it accepts is
 * deliberately narrow — only {@code alg=ES256}, only {@code tt=runner}, only the
 * audience of this runner, and only a token that is not yet expired and not more than
 * {@link #MAX_LIFETIME} from expiring (a leaked long-lived token is useless).
 */
public final class RunnerTokens {

    /** Longest remaining lifetime accepted; the brain mints for about a minute. */
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(5);

    /** Tolerated clock difference between brain and runner. */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private static final String ALGORITHM = "ES256";
    private static final int SIGNATURE_BYTES = 64;

    private RunnerTokens() {}

    /** What a verified token says. */
    public record Claims(
            String tenantId, String projectId, @Nullable String rootDir, String jti, Instant expiresAt) {}

    /**
     * Verifies {@code token} against any of the tenant's current public keys (several
     * during a key rotation).
     *
     * @param runnerId this runner's id — the token's audience must name it
     * @param now      the time to judge expiry by
     * @param replay   remembers used {@code jti}s
     * @throws RunnerProtocolException {@link Kind#TOKEN} for any reason to refuse
     */
    public static Claims verify(String token, List<PublicKey> keys, String runnerId, Instant now, JtiCache replay) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw refuse("not a compact JWS");
        }
        JsonNode header = json(parts[0], "header");
        JsonNode alg = header.get("alg");
        if (alg == null || !ALGORITHM.equals(alg.asString())) {
            throw refuse("algorithm is not " + ALGORITHM);
        }
        verifySignature(parts, keys);

        JsonNode payload = json(parts[1], "payload");
        if (!RunnerTokenClaims.TYPE.equals(text(payload, RunnerTokenClaims.CLAIM_TYPE))) {
            throw refuse("not a runner token");
        }
        if (!hasAudience(payload.get(RunnerTokenClaims.CLAIM_AUDIENCE), RunnerTokenClaims.audience(runnerId))) {
            throw refuse("not addressed to this runner");
        }
        Instant expiresAt = expiry(payload);
        if (!expiresAt.plus(CLOCK_SKEW).isAfter(now)) {
            throw refuse("expired");
        }
        if (expiresAt.isAfter(now.plus(MAX_LIFETIME).plus(CLOCK_SKEW))) {
            throw refuse("lives longer than a runner token may");
        }
        String tenantId = required(payload, RunnerTokenClaims.CLAIM_TENANT_ID);
        String projectId = required(payload, RunnerTokenClaims.CLAIM_PROJECT_ID);
        String jti = required(payload, RunnerTokenClaims.CLAIM_ID);
        // Last: only a token with a valid signature may occupy a slot in the replay cache.
        if (!replay.firstUse(jti, expiresAt, now)) {
            throw refuse("already used");
        }
        return new Claims(tenantId, projectId, text(payload, RunnerTokenClaims.CLAIM_ROOT), jti, expiresAt);
    }

    /**
     * The {@code jti} claimed by a token — <b>not verified</b>, so never a basis for any
     * decision. It exists for one purpose: when a token is refused, the runner still
     * answers (encrypted to the request's one-time key) and that answer must name the
     * call it belongs to. Only the brain that holds the key can read it.
     */
    public static Optional<String> unverifiedJti(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    text(Frames.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1])), RunnerTokenClaims.CLAIM_ID));
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void verifySignature(String[] parts, List<PublicKey> keys) {
        byte[] signature = decode(parts[2], "signature");
        if (signature.length != SIGNATURE_BYTES) {
            throw refuse("signature has the wrong length");
        }
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        for (PublicKey key : keys) {
            if (!(key instanceof ECPublicKey ec) || ec.getParams().getOrder().bitLength() != 256) {
                continue; // a tenant key of another kind is not for this algorithm
            }
            try {
                Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
                verifier.initVerify(key);
                verifier.update(signed);
                if (verifier.verify(signature)) {
                    return;
                }
            } catch (GeneralSecurityException e) {
                // an unusable key never verifies; try the next
            }
        }
        throw refuse("signature does not verify");
    }

    private static JsonNode json(String segment, String what) {
        try {
            return Frames.MAPPER.readTree(decode(segment, what));
        } catch (JacksonException e) {
            throw refuse(what + " is not JSON", e);
        }
    }

    private static byte[] decode(String segment, String what) {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw refuse(what + " is not base64url", e);
        }
    }

    private static boolean hasAudience(@Nullable JsonNode aud, String expected) {
        if (aud == null) {
            return false;
        }
        if (aud.isString()) {
            return expected.equals(aud.asString());
        }
        if (aud.isArray()) {
            for (JsonNode entry : aud) {
                if (entry.isString() && expected.equals(entry.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Instant expiry(JsonNode payload) {
        JsonNode exp = payload.get(RunnerTokenClaims.CLAIM_EXPIRES);
        if (exp == null || !exp.isNumber()) {
            throw refuse("has no expiry");
        }
        return Instant.ofEpochSecond(exp.asLong());
    }

    private static String required(JsonNode payload, String claim) {
        String value = text(payload, claim);
        if (value == null) {
            throw refuse("claim '" + claim + "' is missing");
        }
        return value;
    }

    private static @Nullable String text(JsonNode payload, String claim) {
        JsonNode node = payload.get(claim);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            return null;
        }
        return node.asString();
    }

    private static RunnerProtocolException refuse(String why) {
        return new RunnerProtocolException(Kind.TOKEN, "Token refused: " + why);
    }

    private static RunnerProtocolException refuse(String why, Throwable cause) {
        return new RunnerProtocolException(Kind.TOKEN, "Token refused: " + why, cause);
    }
}
