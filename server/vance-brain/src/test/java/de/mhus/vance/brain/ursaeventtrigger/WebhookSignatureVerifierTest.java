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
