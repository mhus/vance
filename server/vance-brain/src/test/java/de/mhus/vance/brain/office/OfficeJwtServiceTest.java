package de.mhus.vance.brain.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.MacAlgorithm;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link OfficeJwtService}. The service is
 * stateless; we construct it directly and exercise sign/verify
 * round-trips against in-memory secrets.
 */
class OfficeJwtServiceTest {

    private static OfficeSettings.Snapshot snapshot(String secret, String algorithm) {
        return new OfficeSettings.Snapshot("onlyoffice", "http://office.local",
                secret, algorithm, "");
    }

    @Test
    void macAlgorithm_picksRightVariant() {
        assertThat(OfficeJwtService.macAlgorithm(null)).isEqualTo(Jwts.SIG.HS256);
        assertThat(OfficeJwtService.macAlgorithm("HS256")).isEqualTo(Jwts.SIG.HS256);
        assertThat(OfficeJwtService.macAlgorithm("hs384")).isEqualTo(Jwts.SIG.HS384);
        assertThat(OfficeJwtService.macAlgorithm("HS512")).isEqualTo(Jwts.SIG.HS512);
    }

    @Test
    void macAlgorithm_unsupportedThrows() {
        assertThatThrownBy(() -> OfficeJwtService.macAlgorithm("RS256"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported office.jwtAlgorithm");
    }

    @Test
    void hmacKey_shortSecretIsRejected() {
        OfficeSettings.Snapshot s = snapshot("too-short", "HS256");
        assertThatThrownBy(() -> OfficeJwtService.hmacKey(s))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes long for HS256");
    }

    @Test
    void hmacKey_blankSecretIsRejected() {
        OfficeSettings.Snapshot s = snapshot("", "HS256");
        assertThatThrownBy(() -> OfficeJwtService.hmacKey(s))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void sign_then_verify_roundTrips_claimsAndType() {
        OfficeJwtService svc = new OfficeJwtService();
        // 32-byte secret minimum for HS256
        OfficeSettings.Snapshot s = snapshot("01234567890123456789012345678901", "HS256");

        String token = svc.sign(s, "download",
                Map.of("docId", "abc", "tenant", "acme"));
        assertThat(token).isNotBlank();

        Claims claims = svc.verify(s, token);
        assertThat(claims).isNotNull();
        assertThat(claims.get("docId", String.class)).isEqualTo("abc");
        assertThat(claims.get("tenant", String.class)).isEqualTo("acme");
        assertThat(claims.get("typ", String.class)).isEqualTo("download");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void verify_rejectsTamperedToken() {
        OfficeJwtService svc = new OfficeJwtService();
        OfficeSettings.Snapshot s = snapshot("01234567890123456789012345678901", "HS256");
        String token = svc.sign(s, "download", Map.of("docId", "abc"));
        // Flip a char in the signature segment
        String tampered = token.substring(0, token.length() - 4)
                + "XXXX";
        assertThat(svc.verify(s, tampered)).isNull();
    }

    @Test
    void verify_withDifferentSecretFails() {
        OfficeJwtService svc = new OfficeJwtService();
        OfficeSettings.Snapshot signer = snapshot("01234567890123456789012345678901", "HS256");
        OfficeSettings.Snapshot verifier = snapshot("OTHER567890123456789012345678901", "HS256");
        String token = svc.sign(signer, "download", Map.of("docId", "abc"));
        assertThat(svc.verify(verifier, token)).isNull();
    }

    @Test
    void verify_nullOrBlankReturnsNull() {
        OfficeJwtService svc = new OfficeJwtService();
        OfficeSettings.Snapshot s = snapshot("01234567890123456789012345678901", "HS256");
        assertThat(svc.verify(s, null)).isNull();
        assertThat(svc.verify(s, "")).isNull();
        assertThat(svc.verify(s, "   ")).isNull();
    }
}
