package de.mhus.vance.foot.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The browser login of the foot CLI against the brain's OAuth server —
 * RFC 8252 for native apps: authorization code + PKCE, a loopback redirect on
 * a free port, the user logs in in their own browser. The password never
 * passes through the CLI, and whatever the browser login supports (later:
 * 2FA, SSO) works here without the CLI knowing.
 *
 * <p>Asks for the {@code account} scope as the built-in client
 * {@value #CLIENT_ID} — a full-account credential, which the server only
 * hands to its first-party clients.
 */
@Component
@Slf4j
public class OAuthBrowserLogin {

    /** The first-party client id the brain knows for this CLI. */
    public static final String CLIENT_ID = "vancetope-foot";

    static final String CALLBACK_PATH = "/callback";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthClient client;
    private final Consumer<String> browserOpener;

    /** Outcome of a browser login. */
    public record Result(OAuthClient.Endpoints endpoints, OAuthClient.Tokens tokens, String username) {}

    /** How the loopback listener answers one request. */
    enum CallbackDecision {
        /** Our redirect (exact path, matching state) — completes the login. */
        ACCEPT,
        /** Any other path — 404, keep waiting. */
        NOT_FOUND,
        /** Not a GET — 405, keep waiting. */
        METHOD_NOT_ALLOWED,
        /** The callback path without our state — 400, keep waiting. */
        BAD_REQUEST
    }

    @Autowired
    public OAuthBrowserLogin(OAuthClient client) {
        this(client, OAuthBrowserLogin::openBrowser);
    }

    /** Test constructor — the browser opener is replaced, so no real browser starts. */
    OAuthBrowserLogin(OAuthClient client, Consumer<String> browserOpener) {
        this.client = client;
        this.browserOpener = browserOpener;
    }

    /**
     * Run the login: discover, validate the discovered endpoints, listen on a
     * loopback port, open the browser, wait for the redirect, exchange the
     * code.
     *
     * @param allowInsecureTransport {@code vance.brain.allowInsecureTransport} —
     *                               applied to every discovered endpoint, like to the brain base
     * @param showUrl receives the authorization URL — shown to the user in
     *                case the browser cannot be opened automatically
     */
    public Result run(
            String httpBase, String tenant, boolean allowInsecureTransport, Consumer<String> showUrl, Duration timeout)
            throws Exception {
        OAuthClient.Endpoints endpoints = client.discover(httpBase, tenant);
        validateEndpoints(endpoints, httpBase, tenant, allowInsecureTransport);
        String verifier = random(32);
        String state = random(16);

        CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
        // Explicitly IPv4 loopback: the redirect URI names 127.0.0.1, and a
        // listener on another interface (or on ::1 only) would either be
        // reachable from outside or never see the redirect.
        HttpServer server = HttpServer.create(new InetSocketAddress(loopbackV4(), 0), 0);
        server.createContext("/", exchange -> answer(exchange, state, callback));
        server.start();
        try {
            String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK_PATH;
            String url = authorizeUrl(endpoints.authorizationEndpoint(), redirectUri, challenge(verifier), state);
            requireHttpUrl(url);
            showUrl.accept(url);
            browserOpener.accept(url);

            Map<String, String> params;
            try {
                params = callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IllegalStateException(
                        "No answer from the browser within " + timeout.toMinutes() + " minutes", e);
            }
            String code = checkCallback(params, state, endpoints.issuer());
            OAuthClient.Tokens tokens = client.exchangeCode(endpoints, CLIENT_ID, code, redirectUri, verifier);
            return new Result(endpoints, tokens, subjectOf(tokens.accessToken()));
        } finally {
            server.stop(0);
        }
    }

    /**
     * Validate the discovery document before anything of it is used: the
     * issuer must be exactly the one the discovery URL was formed from
     * (RFC 8414 §3.3 — {@code {httpBase}/brain/{tenant}}), and every endpoint
     * must be an http(s) URL on the brain's own origin that passes the same
     * transport rule as the brain base. A document that sends the browser or
     * the refresh token anywhere else is refused.
     */
    static void validateEndpoints(
            OAuthClient.Endpoints endpoints, String httpBase, String tenant, boolean allowInsecureTransport) {
        String base = trimSlash(httpBase);
        String expectedIssuer = base + "/brain/" + tenant;
        if (!expectedIssuer.equals(endpoints.issuer())) {
            throw new IllegalStateException("The OAuth discovery of " + base + " names the issuer '"
                    + endpoints.issuer() + "', expected '" + expectedIssuer
                    + "' — refusing to sign in (is vance.web.public-base-url the URL you entered?).");
        }
        String baseOrigin = origin(base, "brain URL");
        checkEndpoint("authorization_endpoint", endpoints.authorizationEndpoint(), baseOrigin, allowInsecureTransport);
        checkEndpoint("token_endpoint", endpoints.tokenEndpoint(), baseOrigin, allowInsecureTransport);
        String revocation = endpoints.revocationEndpoint();
        if (revocation != null) {
            checkEndpoint("revocation_endpoint", revocation, baseOrigin, allowInsecureTransport);
        }
    }

    private static void checkEndpoint(String name, String url, String baseOrigin, boolean allowInsecureTransport) {
        String origin = origin(url, name);
        if (!origin.equals(baseOrigin)) {
            throw new IllegalStateException("The OAuth discovery names a " + name + " outside the brain ('" + url
                    + "' is not on " + baseOrigin + ") — refusing to sign in.");
        }
        TransportGuard.assertAllowed(url, null, allowInsecureTransport, false, msg -> {});
    }

    /**
     * {@code scheme://host:port} of an absolute http(s) URL, lower-cased with
     * the default port filled in.
     *
     * @throws IllegalStateException for anything else (other scheme, no host,
     *                               user-info, malformed)
     */
    static String origin(String url, String what) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The " + what + " is not a valid URL: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalStateException("The " + what + " is not an http(s) URL: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("The " + what + " has no host: " + url);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalStateException("The " + what + " carries user info: " + url);
        }
        int port = uri.getPort() != -1 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Guard before a URL leaves for the platform opener: only an absolute http(s) URL. */
    static void requireHttpUrl(String url) {
        origin(url, "authorization URL");
    }

    /**
     * Decide how the loopback listener answers a request. Only a GET on the
     * exact callback path carrying our {@code state} (compared in constant
     * time) is accepted; everything else is answered and ignored, so a stray
     * or hostile local request cannot end the login — the user's real
     * redirect still can, until the timeout.
     */
    static CallbackDecision decide(String method, String path, Map<String, String> params, String expectedState) {
        if (!CALLBACK_PATH.equals(path)) {
            return CallbackDecision.NOT_FOUND;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            return CallbackDecision.METHOD_NOT_ALLOWED;
        }
        return stateMatches(params.get("state"), expectedState)
                ? CallbackDecision.ACCEPT
                : CallbackDecision.BAD_REQUEST;
    }

    /** Constant-time comparison of the returned {@code state} with ours. */
    static boolean stateMatches(@Nullable String actual, String expected) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validate the redirect: state must round-trip, {@code iss} must be
     * present and name the issuer discovered (RFC 9207 — the brain always
     * sends it, and a response from another server is not ours to use), and
     * either a code or an error is there.
     *
     * @return the authorization code
     */
    static String checkCallback(Map<String, String> params, String expectedState, String expectedIssuer) {
        if (!stateMatches(params.get("state"), expectedState)) {
            throw new IllegalStateException("The sign-in answer does not belong to this login (state mismatch)");
        }
        String iss = params.get("iss");
        if (iss == null) {
            throw new IllegalStateException("The sign-in answer does not name its server (no iss) — refused");
        }
        if (!iss.equals(expectedIssuer)) {
            throw new IllegalStateException("The sign-in answer came from another server (" + iss + ")");
        }
        String error = params.get("error");
        if (error != null) {
            String description = params.get("error_description");
            throw new IllegalStateException(
                    "Sign-in refused: " + error + (description == null ? "" : " — " + description));
        }
        String code = params.get("code");
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("The sign-in answer carries no code");
        }
        return code;
    }

    static String authorizeUrl(String authorizationEndpoint, String redirectUri, String challenge, String state) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", CLIENT_ID);
        q.put("redirect_uri", redirectUri);
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        q.put("state", state);
        q.put("scope", "account");
        StringBuilder sb = new StringBuilder(authorizationEndpoint);
        char sep = authorizationEndpoint.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> e : q.entrySet()) {
            sb.append(sep)
                    .append(e.getKey())
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }

    /** {@code BASE64URL(SHA256(verifier))} (RFC 7636 S256). */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static Map<String, String> parseQuery(@Nullable String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(key, value);
        }
        return out;
    }

    /**
     * The {@code sub} of a JWT, read without verifying — the CLI only needs it
     * to name the account it is logged in as; the brain verifies every use.
     */
    static String subjectOf(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalStateException("access token is not a JWT");
        }
        JsonNode payload = JsonMapper.builder()
                .build()
                .readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        JsonNode sub = payload.get("sub");
        if (sub == null || !sub.isString()) {
            throw new IllegalStateException("access token carries no subject");
        }
        return sub.asString();
    }

    private static void answer(
            HttpExchange exchange, String expectedState, CompletableFuture<Map<String, String>> callback)
            throws IOException {
        try (exchange) {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            CallbackDecision decision =
                    decide(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), params, expectedState);
            switch (decision) {
                case NOT_FOUND -> respond(exchange, 404, "Not found", "");
                case METHOD_NOT_ALLOWED -> respond(exchange, 405, "Method not allowed", "");
                case BAD_REQUEST -> {
                    log.trace("loopback callback without the expected state ignored");
                    respond(exchange, 400, "Not this sign-in", "This answer does not belong to the running login.");
                }
                case ACCEPT -> {
                    callback.complete(params);
                    boolean ok = params.containsKey("code") && !params.containsKey("error");
                    respond(
                            exchange,
                            200,
                            ok ? "Signed in" : "Sign-in failed",
                            ok
                                    ? "You can close this tab and return to the terminal."
                                    : "Return to the terminal for details.");
                }
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String title, String text) throws IOException {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Vancetope</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:30rem;margin:4rem auto;padding:0 1rem}"
                + "</style></head><body><h1>" + title + "</h1><p>" + text + "</p></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static InetAddress loopbackV4() throws UnknownHostException {
        return InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1});
    }

    private static String trimSlash(String value) {
        String v = value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** Best effort: the platform's URL opener; the URL is shown to the user either way. */
    private static void openBrowser(String url) {
        requireHttpUrl(url);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command;
        if (os.contains("mac")) {
            command = new String[] {"open", url};
        } else if (os.contains("win")) {
            command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
        } else {
            command = new String[] {"xdg-open", url};
        }
        try {
            new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            log.debug("could not open a browser: {}", e.toString());
        }
    }

    private static String random(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
