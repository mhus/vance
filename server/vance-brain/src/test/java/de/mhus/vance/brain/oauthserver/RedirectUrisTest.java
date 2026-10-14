package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedirectUrisTest {

    @Test
    void acceptable_httpsAndLoopbackOnly() {
        assertThat(RedirectUris.isAcceptable("https://claude.ai/api/mcp/auth_callback"))
                .isTrue();
        assertThat(RedirectUris.isAcceptable("http://127.0.0.1:33418/callback")).isTrue();
        assertThat(RedirectUris.isAcceptable("http://localhost/callback")).isTrue();
        assertThat(RedirectUris.isAcceptable("http://[::1]:8080/cb")).isTrue();

        assertThat(RedirectUris.isAcceptable("http://evil.example/cb")).isFalse();
        assertThat(RedirectUris.isAcceptable("https://ok.example/cb#frag")).isFalse();
        assertThat(RedirectUris.isAcceptable("https://user@ok.example/cb")).isFalse();
        assertThat(RedirectUris.isAcceptable("javascript:alert(1)")).isFalse();
        assertThat(RedirectUris.isAcceptable("/relative")).isFalse();
        assertThat(RedirectUris.isAcceptable(null)).isFalse();
    }

    @Test
    void matches_isExactForHttps() {
        assertThat(RedirectUris.matches("https://a.example/cb", "https://a.example/cb"))
                .isTrue();
        assertThat(RedirectUris.matches("https://a.example/cb", "https://a.example/cb2"))
                .isFalse();
        assertThat(RedirectUris.matches("https://a.example/cb", "https://a.example:8443/cb"))
                .isFalse();
        assertThat(RedirectUris.matches("https://a.example/cb", "https://a.example/cb?x=1"))
                .isFalse();
    }

    /** RFC 8252 §7.3: a native app binds a free port per login — only the port may differ. */
    @Test
    void matches_ignoresThePortOnLoopbackOnly() {
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://127.0.0.1:51234/callback"))
                .isTrue();
        assertThat(RedirectUris.matches("http://127.0.0.1:1/callback", "http://127.0.0.1:2/callback"))
                .isTrue();
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://127.0.0.1:5/other"))
                .isFalse();
        assertThat(RedirectUris.matches("http://127.0.0.1/callback", "http://localhost:5/callback"))
                .isFalse();
    }

    @Test
    void loopbackAndHost() {
        assertThat(RedirectUris.isLoopback("http://localhost:3000/cb")).isTrue();
        assertThat(RedirectUris.isLoopback("https://claude.ai/cb")).isFalse();
        assertThat(RedirectUris.host("https://claude.ai/cb")).isEqualTo("claude.ai");
    }

    @Test
    void origin_isNormalizedWithExplicitPort() {
        assertThat(RedirectUris.origin("https://Vance.Example.com/brain/acme"))
                .isEqualTo("https://vance.example.com:443");
        assertThat(RedirectUris.origin("http://localhost:9797")).isEqualTo("http://localhost:9797");
        assertThat(RedirectUris.origin("http://a.example/x")).isEqualTo("http://a.example:80");
        assertThat(RedirectUris.origin("javascript:alert(1)")).isNull();
        assertThat(RedirectUris.origin(null)).isNull();
    }

    @Test
    void acceptable_refusesTheServersOwnOrigin() {
        String own = RedirectUris.origin("https://vance.example.com");

        assertThat(RedirectUris.isAcceptable("https://vance.example.com/login.html", own))
                .isFalse();
        assertThat(RedirectUris.isAcceptable("https://VANCE.example.com:443/oauth-consent.html", own))
                .isFalse();
        assertThat(RedirectUris.isAcceptable("https://vance.example.com:8443/cb", own))
                .isTrue();
        assertThat(RedirectUris.isAcceptable("https://other.example.com/cb", own))
                .isTrue();
        assertThat(RedirectUris.isAcceptable("http://127.0.0.1:3000/cb", own)).isTrue();
        assertThat(RedirectUris.isAcceptable("http://evil.example/cb", own)).isFalse();
        assertThat(RedirectUris.isAcceptable("https://vance.example.com/cb", null))
                .isTrue();
    }
}
