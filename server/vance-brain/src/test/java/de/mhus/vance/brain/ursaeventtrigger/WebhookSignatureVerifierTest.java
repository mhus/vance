package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.ursaevents.WebhookSignatureSpec;
import de.mhus.vance.shared.ursaevents.WebhookSignatureSpec.Encoding;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "topsecret";
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    private static byte[] mac(byte[] signed) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signed);
    }

    private static java.util.Optional<String> check(WebhookSignatureSpec spec, Map<String, String> headers) {
        return WebhookSignatureVerifier.refusal(spec, SECRET, BODY, headers::get, NOW);
    }

    @Test
    void aHexSignatureWithAPrefixHolds() throws Exception {
        WebhookSignatureSpec spec = new WebhookSignatureSpec("X-Sig", "k", "sha256=", Encoding.HEX, null, 300);

        assertThat(check(spec, Map.of("X-Sig", "sha256=" + HexFormat.of().formatHex(mac(BODY)))))
                .isEmpty();
    }

    @Test
    void aBase64SignatureHolds() throws Exception {
        WebhookSignatureSpec spec = new WebhookSignatureSpec("X-Sig", "k", null, Encoding.BASE64, null, 300);

        assertThat(check(spec, Map.of("X-Sig", Base64.getEncoder().encodeToString(mac(BODY)))))
                .isEmpty();
    }

    @Test
    void aChangedBodyDoesNotHold() throws Exception {
        WebhookSignatureSpec spec = new WebhookSignatureSpec("X-Sig", "k", null, Encoding.HEX, null, 300);
        String sig = HexFormat.of().formatHex(mac("{\"a\":2}".getBytes(StandardCharsets.UTF_8)));

        assertThat(check(spec, Map.of("X-Sig", sig))).hasValue("signature does not match");
    }

    @Test
    void aMissingMisprefixedOrUndecodableSignatureIsRefusedWithAReason() {
        WebhookSignatureSpec spec = new WebhookSignatureSpec("X-Sig", "k", "sha256=", Encoding.HEX, null, 300);

        assertThat(check(spec, Map.of())).hasValueSatisfying(r -> assertThat(r).contains("X-Sig"));
        assertThat(check(spec, Map.of("X-Sig", "abc")))
                .hasValueSatisfying(r -> assertThat(r).contains("sha256="));
        assertThat(check(spec, Map.of("X-Sig", "sha256=zz")))
                .hasValueSatisfying(r -> assertThat(r).contains("hex"));
    }

    /**
     * Slack's form: header {@code X-Slack-Signature: v0=<hex>} over {@code v0:<timestamp>:<body>}, with the
     * timestamp in {@code X-Slack-Request-Timestamp}. Computed here with the same recipe, not taken from Slack's
     * documentation — it shows the template is honoured, not that Slack's published vector verifies.
     */
    @Test
    void slacksFormIsSpelledOutBySignedText() throws Exception {
        WebhookSignatureSpec spec = new WebhookSignatureSpec(
                "X-Slack-Signature",
                "k",
                "v0=",
                Encoding.HEX,
                "X-Slack-Request-Timestamp",
                300,
                "v0:{timestamp}:{body}");
        long fresh = NOW.getEpochSecond() - 5;
        byte[] signed =
                ("v0:" + fresh + ":" + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of(
                "X-Slack-Signature",
                "v0=" + HexFormat.of().formatHex(mac(signed)),
                "X-Slack-Request-Timestamp",
                Long.toString(fresh));

        assertThat(check(spec, headers)).isEmpty();
        // The default form for a timestamped signature is not Slack's, and does not hold for it.
        WebhookSignatureSpec stripeForm = new WebhookSignatureSpec(
                "X-Slack-Signature", "k", "v0=", Encoding.HEX, "X-Slack-Request-Timestamp", 300);
        assertThat(check(stripeForm, headers)).hasValue("signature does not match");
    }

    @Test
    void theDefaultsAreTheBodyAloneAndTimestampDotBody() {
        assertThat(new WebhookSignatureSpec("h", "k", null, Encoding.HEX, null, 300).effectiveSignedText())
                .isEqualTo("{body}");
        assertThat(new WebhookSignatureSpec("h", "k", null, Encoding.HEX, "t", 300).effectiveSignedText())
                .isEqualTo("{timestamp}.{body}");
        assertThat(new WebhookSignatureSpec("h", "k", null, Encoding.HEX, "t", 300, "{body}").effectiveSignedText())
                .isEqualTo("{body}");
    }

    @Test
    void theBodyGoesIntoTheSignedTextAsReceivedEvenWhenItIsNotValidUtf8() throws Exception {
        byte[] binary = {(byte) 0xff, (byte) 0xfe, 'a', 0, (byte) 0x80};
        WebhookSignatureSpec spec = new WebhookSignatureSpec("X-Sig", "k", null, Encoding.HEX, null, 300, "x:{body}:y");
        byte[] signed = new byte[binary.length + 4];
        System.arraycopy("x:".getBytes(StandardCharsets.UTF_8), 0, signed, 0, 2);
        System.arraycopy(binary, 0, signed, 2, binary.length);
        System.arraycopy(":y".getBytes(StandardCharsets.UTF_8), 0, signed, 2 + binary.length, 2);
        String sig = HexFormat.of().formatHex(mac(signed));

        assertThat(WebhookSignatureVerifier.refusal(spec, SECRET, binary, Map.of("X-Sig", sig)::get, NOW))
                .isEmpty();
    }

    @Test
    void renderFillsTheTemplateLiterally() {
        byte[] out = WebhookSignatureVerifier.render(
                "a{timestamp}b{body}c{body}", "99", "XY".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("a99bXYcXY");
    }

    @Test
    void aTimestampedSignatureCoversTheTimestampAndRefusesAStaleOne() throws Exception {
        WebhookSignatureSpec spec = new WebhookSignatureSpec("X-Sig", "k", null, Encoding.HEX, "X-Ts", 300);
        long fresh = NOW.getEpochSecond() - 10;
        byte[] signed = (fresh + "." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        String sig = HexFormat.of().formatHex(mac(signed));

        assertThat(check(spec, Map.of("X-Sig", sig, "X-Ts", Long.toString(fresh))))
                .isEmpty();

        // The same request an hour later is a replay: the signature is right and it is still refused.
        long stale = NOW.getEpochSecond() - 3600;
        byte[] staleSigned = (stale + "." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        assertThat(check(
                        spec,
                        Map.of("X-Sig", HexFormat.of().formatHex(mac(staleSigned)), "X-Ts", Long.toString(stale))))
                .hasValueSatisfying(r -> assertThat(r).contains("timestamp"));
        // And a timestamp the signature does not cover does not hold.
        assertThat(check(spec, Map.of("X-Sig", sig, "X-Ts", Long.toString(fresh + 1))))
                .isPresent();
        assertThat(check(spec, Map.of("X-Sig", sig))).isPresent();
    }
}
