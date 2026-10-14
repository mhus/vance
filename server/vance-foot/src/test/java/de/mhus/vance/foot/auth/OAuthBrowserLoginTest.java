package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OAuthBrowserLoginTest {

    private static final String ISSUER = "https://brain.example.com/brain/acme";

    @Test
    void challenge_matchesTheRfc7636Example() {
        assertThat(OAuthBrowserLogin.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void authorizeUrl_carriesPkceStateAndTheAccountScope() {
        String url = OAuthBrowserLogin.authorizeUrl(
                ISSUER + "/oauth/authorize", "http://127.0.0.1:5555/callback", "CH", "ST");

        assertThat(url)
                .startsWith(ISSUER + "/oauth/authorize?response_type=code&client_id=vancetope-foot")
                .contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5555%2Fcallback")
                .contains("code_challenge=CH&code_challenge_method=S256&state=ST&scope=account");
    }

    @Test
    void checkCallback_returnsTheCode() {
        assertThat(OAuthBrowserLogin.checkCallback(Map.of("code", "C", "state", "ST", "iss", ISSUER), "ST", ISSUER))
                .isEqualTo("C");
    }

    @Test
    void checkCallback_refusesForeignStateIssuerAndErrors() {
        assertThatThrownBy(() -> OAuthBrowserLogin.checkCallback(Map.of("code", "C", "state", "X"), "ST", ISSUER))
                .hasMessageContaining("state");
        assertThatThrownBy(() -> OAuthBrowserLogin.checkCallback(
                        Map.of("code", "C", "state", "ST", "iss", "https://evil.example"), "ST", ISSUER))
                .hasMessageContaining("another server");
        assertThatThrownBy(() ->
                        OAuthBrowserLogin.checkCallback(Map.of("error", "access_denied", "state", "ST"), "ST", ISSUER))
                .hasMessageContaining("access_denied");
    }

    @Test
    void parseQuery_decodes() {
        assertThat(OAuthBrowserLogin.parseQuery("code=a%2Bb&state=x%20y&empty"))
                .containsEntry("code", "a+b")
                .containsEntry("state", "x y")
                .containsEntry("empty", "");
    }

    @Test
    void subjectOf_readsSub() {
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"mike\",\"tid\":\"acme\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(OAuthBrowserLogin.subjectOf("h." + payload + ".s")).isEqualTo("mike");
    }
}
