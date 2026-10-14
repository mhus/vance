package de.mhus.vance.foot.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
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

    /** Outcome of a browser login. */
    public record Result(OAuthClient.Endpoints endpoints, OAuthClient.Tokens tokens, String username) {}

    public OAuthBrowserLogin(OAuthClient client) {
        this.client = client;
    }

    /**
     * Run the login: discover, listen on a loopback port, open the browser,
     * wait for the redirect, exchange the code.
     *
     * @param showUrl receives the authorization URL — shown to the user in
     *                case the browser cannot be opened automatically
     */
    public Result run(String httpBase, String tenant, Consumer<String> showUrl, Duration timeout) throws Exception {
        OAuthClient.Endpoints endpoints = client.discover(httpBase, tenant);
        String verifier = random(32);
        String state = random(16);

        CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(CALLBACK_PATH, exchange -> answer(exchange, callback));
        server.start();
        try {
            String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK_PATH;
            String url = authorizeUrl(endpoints.authorizationEndpoint(), redirectUri, challenge(verifier), state);
            showUrl.accept(url);
            openBrowser(url);

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
     * Validate the redirect: state must round-trip, an {@code iss} present must
     * name the issuer discovered (RFC 9207 — a response from another server
     * is not ours to use), and either a code or an error is there.
     *
     * @return the authorization code
     */
    static String checkCallback(Map<String, String> params, String expectedState, String expectedIssuer) {
        String iss = params.get("iss");
        if (iss != null && !iss.equals(expectedIssuer)) {
            throw new IllegalStateException("The sign-in answer came from another server (" + iss + ")");
        }
        if (!expectedState.equals(params.get("state"))) {
            throw new IllegalStateException("The sign-in answer does not belong to this login (state mismatch)");
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

    private static void answer(HttpExchange exchange, CompletableFuture<Map<String, String>> callback)
            throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        boolean ok = params.containsKey("code") && !params.containsKey("error");
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Vancetope</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:30rem;margin:4rem auto;padding:0 1rem}"
                + "</style></head><body><h1>" + (ok ? "Signed in" : "Sign-in failed") + "</h1><p>"
                + (ok ? "You can close this tab and return to the terminal." : "Return to the terminal for details.")
                + "</p></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        callback.complete(params);
    }

    /** Best effort: the platform's URL opener; the URL is shown to the user either way. */
    private static void openBrowser(String url) {
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
