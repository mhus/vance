package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
        assertThatThrownBy(() ->
                        OAuthBrowserLogin.checkCallback(Map.of("code", "C", "state", "X", "iss", ISSUER), "ST", ISSUER))
                .hasMessageContaining("state");
        assertThatThrownBy(() -> OAuthBrowserLogin.checkCallback(
                        Map.of("code", "C", "state", "ST", "iss", "https://evil.example"), "ST", ISSUER))
                .hasMessageContaining("another server");
        assertThatThrownBy(() -> OAuthBrowserLogin.checkCallback(
                        Map.of("error", "access_denied", "state", "ST", "iss", ISSUER), "ST", ISSUER))
                .hasMessageContaining("access_denied");
    }

    @Test
    void checkCallback_requiresTheIssuer() {
        assertThatThrownBy(() -> OAuthBrowserLogin.checkCallback(Map.of("code", "C", "state", "ST"), "ST", ISSUER))
                .hasMessageContaining("iss");
    }

    // ── discovery validation ────────────────────────────────────────────

    private static OAuthClient.Endpoints endpoints(String issuer, String authorize, String token, String revoke) {
        return new OAuthClient.Endpoints(issuer, authorize, token, revoke);
    }

    private static OAuthClient.Endpoints goodEndpoints() {
        return endpoints(ISSUER, ISSUER + "/oauth/authorize", ISSUER + "/oauth/token", ISSUER + "/oauth/revoke");
    }

    @Test
    void validateEndpoints_acceptsTheBrainsOwnEndpoints() {
        OAuthBrowserLogin.validateEndpoints(goodEndpoints(), "https://brain.example.com/", "acme", false);
        OAuthBrowserLogin.validateEndpoints(
                endpoints(
                        ISSUER,
                        ISSUER + "/oauth/authorize",
                        "https://BRAIN.example.com:443/brain/acme/oauth/token",
                        null),
                "https://brain.example.com",
                "acme",
                false);
    }

    @Test
    void validateEndpoints_refusesAnIssuerThatIsNotTheDiscoveryUrl() {
        assertThatThrownBy(() -> OAuthBrowserLogin.validateEndpoints(
                        goodEndpoints(), "https://brain.example.com", "other", false))
                .hasMessageContaining("issuer");
        assertThatThrownBy(() -> OAuthBrowserLogin.validateEndpoints(
                        goodEndpoints(), "https://brain.example.org", "acme", false))
                .hasMessageContaining("issuer");
    }

    @Test
    void validateEndpoints_refusesEndpointsOffTheBrainsOrigin() {
        assertThatThrownBy(() -> OAuthBrowserLogin.validateEndpoints(
                        endpoints(ISSUER, "https://evil.example/authorize", ISSUER + "/oauth/token", null),
                        "https://brain.example.com",
                        "acme",
                        false))
                .hasMessageContaining("authorization_endpoint");
        assertThatThrownBy(() -> OAuthBrowserLogin.validateEndpoints(
                        endpoints(ISSUER, ISSUER + "/oauth/authorize", "https://brain.example.com:8443/token", null),
                        "https://brain.example.com",
                        "acme",
                        false))
                .hasMessageContaining("token_endpoint");
        assertThatThrownBy(() -> OAuthBrowserLogin.validateEndpoints(
                        endpoints(
                                ISSUER,
                                ISSUER + "/oauth/authorize",
                                ISSUER + "/oauth/token",
                                "http://brain.example.com/brain/acme/oauth/revoke"),
                        "https://brain.example.com",
                        "acme",
                        false))
                .hasMessageContaining("revocation_endpoint");
    }

    @Test
    void validateEndpoints_refusesNonHttpSchemesAndUserInfo() {
        assertThatThrownBy(() -> OAuthBrowserLogin.validateEndpoints(
                        endpoints(ISSUER, "javascript:alert(1)", ISSUER + "/oauth/token", null),
                        "https://brain.example.com",
                        "acme",
                        false))
                .hasMessageContaining("http(s)");
        assertThatThrownBy(() -> OAuthBrowserLogin.validateEndpoints(
                        endpoints(
                                ISSUER,
                                "https://user@brain.example.com/brain/acme/oauth/authorize",
                                ISSUER + "/oauth/token",
                                null),
                        "https://brain.example.com",
                        "acme",
                        false))
                .hasMessageContaining("user info");
    }

    @Test
    void validateEndpoints_appliesTheTransportRule() {
        String issuer = "http://brain.lan/brain/acme";
        OAuthClient.Endpoints plain = endpoints(issuer, issuer + "/oauth/authorize", issuer + "/oauth/token", null);

        assertThatThrownBy(() -> OAuthBrowserLogin.validateEndpoints(plain, "http://brain.lan", "acme", false))
                .hasMessageContaining("plaintext");
        OAuthBrowserLogin.validateEndpoints(plain, "http://brain.lan", "acme", true);
        String local = "http://127.0.0.1:9080/brain/acme";
        OAuthBrowserLogin.validateEndpoints(
                endpoints(local, local + "/oauth/authorize", local + "/oauth/token", local + "/oauth/revoke"),
                "http://127.0.0.1:9080",
                "acme",
                false);
    }

    // ── loopback listener ───────────────────────────────────────────────

    @Test
    void decide_acceptsOnlyTheExactPathWithOurState() {
        assertThat(OAuthBrowserLogin.decide("GET", "/callback", Map.of("state", "ST"), "ST"))
                .isEqualTo(OAuthBrowserLogin.CallbackDecision.ACCEPT);
        assertThat(OAuthBrowserLogin.decide("GET", "/callback/x", Map.of("state", "ST"), "ST"))
                .isEqualTo(OAuthBrowserLogin.CallbackDecision.NOT_FOUND);
        assertThat(OAuthBrowserLogin.decide("GET", "/", Map.of("state", "ST"), "ST"))
                .isEqualTo(OAuthBrowserLogin.CallbackDecision.NOT_FOUND);
        assertThat(OAuthBrowserLogin.decide("POST", "/callback", Map.of("state", "ST"), "ST"))
                .isEqualTo(OAuthBrowserLogin.CallbackDecision.METHOD_NOT_ALLOWED);
        assertThat(OAuthBrowserLogin.decide("GET", "/callback", Map.of("state", "XX"), "ST"))
                .isEqualTo(OAuthBrowserLogin.CallbackDecision.BAD_REQUEST);
        assertThat(OAuthBrowserLogin.decide("GET", "/callback", Map.of(), "ST"))
                .isEqualTo(OAuthBrowserLogin.CallbackDecision.BAD_REQUEST);
    }

    @Test
    void run_ignoresStrayRequestsAndFinishesOnTheRealRedirect() throws Exception {
        String httpBase = "https://brain.example.com";
        String jwt = "h."
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"sub\":\"mike\"}".getBytes(StandardCharsets.UTF_8))
                + ".s";
        List<String> exchangedCodes = new CopyOnWriteArrayList<>();
        OAuthClient client = new OAuthClient() {
            @Override
            public Endpoints discover(String base, String tenant) {
                return goodEndpoints();
            }

            @Override
            public Tokens exchangeCode(Endpoints e, String clientId, String code, String redirectUri, String v) {
                exchangedCodes.add(code);
                return new Tokens(jwt, 3600, "rt");
            }

            @Override
            public Tokens refresh(String tokenEndpoint, String clientId, String refreshToken) {
                throw new IllegalStateException("not used");
            }

            @Override
            public boolean revoke(String revocationEndpoint, String clientId, String token) {
                return true;
            }
        };
        List<String> opened = new CopyOnWriteArrayList<>();
        List<Integer> strayStatus = new CopyOnWriteArrayList<>();
        HttpClient http =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        OAuthBrowserLogin login = new OAuthBrowserLogin(client, opened::add);
        OAuthBrowserLogin.Result result = login.run(
                httpBase,
                "acme",
                false,
                url -> Thread.ofVirtual().start(() -> {
                    try {
                        Map<String, String> q =
                                OAuthBrowserLogin.parseQuery(URI.create(url).getRawQuery());
                        String redirect = q.get("redirect_uri");
                        assertThat(redirect).startsWith("http://127.0.0.1:").endsWith("/callback");
                        String base = redirect.substring(0, redirect.length() - "/callback".length());
                        strayStatus.add(get(http, base + "/other?code=X&state=" + q.get("state")));
                        strayStatus.add(get(http, redirect + "?code=X&state=wrong&iss=" + enc(ISSUER)));
                        get(http, redirect + "?code=REAL&state=" + q.get("state") + "&iss=" + enc(ISSUER));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }),
                Duration.ofSeconds(10));

        assertThat(result.username()).isEqualTo("mike");
        assertThat(exchangedCodes).containsExactly("REAL");
        assertThat(strayStatus).containsExactly(404, 400);
        assertThat(opened).hasSize(1);
        assertThat(opened.get(0)).startsWith(ISSUER + "/oauth/authorize?");
    }

    private static int get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
