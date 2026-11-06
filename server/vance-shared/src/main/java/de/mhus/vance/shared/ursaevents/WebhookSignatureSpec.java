package de.mhus.vance.shared.ursaevents;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How a provider signs the webhook it sends: an HMAC-SHA256 of the request body in a header, the way GitHub
 * ({@code X-Hub-Signature-256}, prefix {@code sha256=}) and most others do.
 *
 * <p>YAML ({@code auth.signature}):
 * <pre>
 *   auth:
 *     signature:
 *       header: X-Hub-Signature-256
 *       secretSetting: webhook.github.secret      # setting key, cascade project → _tenant
 *       prefix: "sha256="                         # optional, cut off the header value
 *       encoding: hex                             # hex (default) | base64
 *       timestampHeader: X-Webhook-Timestamp      # optional — see below
 *       toleranceSeconds: 300
 *       signedText: "{timestamp}.{body}"          # optional — what the HMAC is computed over
 * </pre>
 *
 * <p>With a {@code timestampHeader} a request whose timestamp is further from now than
 * {@code toleranceSeconds} is refused: a captured request cannot be sent again later. Without one nothing stops
 * a replay — which is how several providers sign.
 *
 * <p>{@code signedText} says what the signature covers, with two placeholders: {@code {body}} (the received
 * bytes) and {@code {timestamp}} (the value of the {@code timestampHeader}). Unstated it is
 * {@code {timestamp}.{body}} when there is a {@code timestampHeader} (the Stripe form) and {@code {body}}
 * otherwise (GitHub). Slack signs {@code v0:{timestamp}:{body}} — spelled out here instead of being a special
 * case. {@code {body}} is required, {@code {timestamp}} needs the {@code timestampHeader}, and any other
 * {@code {…}} is refused when the event is loaded.
 *
 * @param header           the header carrying the signature
 * @param secretSettingKey setting that holds the shared secret
 * @param prefix           text before the signature in the header value, or {@code null}
 * @param encoding         how the signature is written
 * @param timestampHeader  header carrying the Unix timestamp (seconds) the signature covers, or {@code null}
 * @param toleranceSeconds how far a timestamp may be from now
 * @param signedText       what the HMAC covers, with {@code {body}} and {@code {timestamp}}; {@code null} for the
 *                         default (see above)
 */
public record WebhookSignatureSpec(
        String header,
        String secretSettingKey,
        @Nullable String prefix,
        Encoding encoding,
        @Nullable String timestampHeader,
        int toleranceSeconds,
        @Nullable String signedText) {

    /** A spec with the default signed text. */
    public WebhookSignatureSpec(
            String header,
            String secretSettingKey,
            @Nullable String prefix,
            Encoding encoding,
            @Nullable String timestampHeader,
            int toleranceSeconds) {
        this(header, secretSettingKey, prefix, encoding, timestampHeader, toleranceSeconds, null);
    }

    /** The template the HMAC is computed over: the stated one, else the form the other settings imply. */
    public String effectiveSignedText() {
        if (signedText != null) return signedText;
        return timestampHeader != null ? "{timestamp}.{body}" : "{body}";
    }

    /** The default tolerance for a timestamp — five minutes, what the providers that sign one use. */
    public static final int DEFAULT_TOLERANCE_SECONDS = 300;

    /** How the HMAC is written in the header. */
    public enum Encoding {
        HEX,
        BASE64
    }

    /** Parses the {@code auth.signature} map; throws {@link IllegalStateException} on a bad block. */
    public static WebhookSignatureSpec parse(Map<?, ?> raw) {
        String header = text(raw, "header");
        String secret = text(raw, "secretSetting");
        if (header == null || secret == null) {
            throw new IllegalStateException("'auth.signature' needs 'header' and 'secretSetting'");
        }
        String encodingRaw = text(raw, "encoding");
        Encoding encoding = Encoding.HEX;
        if (encodingRaw != null) {
            try {
                encoding = Encoding.valueOf(encodingRaw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "unknown 'auth.signature.encoding' '" + encodingRaw + "' (expected: hex | base64)", e);
            }
        }
        String timestampHeader = text(raw, "timestampHeader");
        int tolerance = DEFAULT_TOLERANCE_SECONDS;
        if (raw.get("toleranceSeconds") != null) {
            if (!(raw.get("toleranceSeconds") instanceof Number n) || n.intValue() <= 0) {
                throw new IllegalStateException("'auth.signature.toleranceSeconds' must be a positive number");
            }
            tolerance = n.intValue();
        }
        String signedText = text(raw, "signedText");
        if (signedText != null) {
            if (!signedText.contains("{body}")) {
                throw new IllegalStateException("'auth.signature.signedText' must contain {body}");
            }
            if (signedText.contains("{timestamp}") && timestampHeader == null) {
                throw new IllegalStateException(
                        "'auth.signature.signedText' uses {timestamp}, which needs a 'timestampHeader'");
            }
            if (signedText.replace("{body}", "").replace("{timestamp}", "").matches("(?s).*[{}].*")) {
                throw new IllegalStateException(
                        "'auth.signature.signedText' knows only {body} and {timestamp}, not '" + signedText + "'");
            }
        }
        return new WebhookSignatureSpec(
                header, secret, text(raw, "prefix"), encoding, timestampHeader, tolerance, signedText);
    }

    private static @Nullable String text(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }
}
