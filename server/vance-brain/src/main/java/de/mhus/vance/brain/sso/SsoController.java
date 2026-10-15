package de.mhus.vance.brain.sso;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.sso.SsoOptionsDto;
import de.mhus.vance.api.sso.SsoRedeemRequest;
import de.mhus.vance.brain.access.LoginTokenIssuer;
import de.mhus.vance.brain.access.WebUiCookieService;
import de.mhus.vance.brain.oauthserver.ClientAddresses;
import de.mhus.vance.shared.sso.SsoLoginRequestService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The open SSO endpoints under {@code /brain/_sso/} — no tenant in the path,
 * because at sign-in the tenant is not known yet. Exempted from the access
 * filter; budgeted per client address instead.
 *
 * <ul>
 *   <li>{@code GET discover?email=} — does this address sign in via SSO?</li>
 *   <li>{@code GET options?tenant=} — does this tenant's login page offer a
 *       private Google account?</li>
 *   <li>{@code GET start?email=&tenant=&next=} — off to the identity provider.
 *       With {@code app_redirect} (native app) first a confirmation page that
 *       names the app receiving the login.</li>
 *   <li>{@code GET {provider}/callback} — back from it; on success the same
 *       cookies as the password login, then on to {@code next}. For a native
 *       app ({@code app_redirect} at start) a one-time code to the app instead.</li>
 *   <li>{@code POST redeem} — the app's WebView turns that code (plus its
 *       PKCE verifier) into its own session.</li>
 * </ul>
 *
 * <p>Failures redirect to the login page with a coarse {@code sso_error} —
 * the reason is in the audit log, not in the URL.
 *
 * <p>Budgets per client address, one per kind of request, so a busy NAT
 * address that used up its lookups can still finish the sign-ins it started:
 * lookups ({@code discover}, {@code options}), starts, and redeems each count
 * on their own. The callback is not budgeted — it is single-use by its state
 * and the state cookie, every live one stems from a budgeted start, and
 * refusing it would only throw away a sign-in the person already completed at
 * the provider.
 */
@RestController
public class SsoController {

    /** Browser cookie binding the callback to the browser that started the sign-in. */
    static final String STATE_COOKIE = "vance_sso_state";

    private static final Duration BUDGET_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_TRACKED_ADDRESSES = 10_000;

    private final SsoLoginService ssoLoginService;
    private final WebUiCookieService webUiCookieService;
    private final boolean cookieSecure;
    private final int requestsPerAddress;
    private final Cache<BudgetKey, AtomicInteger> budget;

    /** What a request is counted against. */
    enum Budget {
        LOOKUP,
        START,
        REDEEM
    }

    private record BudgetKey(Budget budget, String address) {}

    public SsoController(
            SsoLoginService ssoLoginService,
            WebUiCookieService webUiCookieService,
            @Value("${vance.web.cookies.secure:true}") boolean cookieSecure,
            @Value("${vance.sso.requests-per-address:60}") int requestsPerAddress) {
        this.ssoLoginService = ssoLoginService;
        this.webUiCookieService = webUiCookieService;
        this.cookieSecure = cookieSecure;
        this.requestsPerAddress = requestsPerAddress;
        this.budget = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_ADDRESSES)
                .expireAfterWrite(BUDGET_WINDOW)
                .build();
    }

    @GetMapping("/brain/_sso/discover")
    public ResponseEntity<SsoOptionsDto> discover(
            @RequestParam(value = "email", required = false) @Nullable String email, HttpServletRequest request) {
        if (!withinBudget(Budget.LOOKUP, request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return options(ssoLoginService.providerForEmail(email), false);
    }

    @GetMapping("/brain/_sso/options")
    public ResponseEntity<SsoOptionsDto> tenantOptions(
            @RequestParam(value = "tenant", required = false) @Nullable String tenant, HttpServletRequest request) {
        if (!withinBudget(Budget.LOOKUP, request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return options(
                tenant == null ? Optional.empty() : ssoLoginService.consumerProvider(tenant),
                ssoLoginService.domainSignInConfigured());
    }

    @GetMapping("/brain/_sso/start")
    public ResponseEntity<?> start(
            @RequestParam(value = "email", required = false) @Nullable String email,
            @RequestParam(value = "tenant", required = false) @Nullable String tenant,
            @RequestParam(value = "next", required = false) @Nullable String next,
            @RequestParam(value = "app_redirect", required = false) @Nullable String appRedirect,
            @RequestParam(value = "app_challenge", required = false) @Nullable String appChallenge,
            HttpServletRequest request) {
        if (!withinBudget(Budget.START, request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        SsoLoginService.Start started = ssoLoginService.start(email, tenant, next, appRedirect, appChallenge);
        // The state cookie lives exactly as long as the pending request.
        long cookieSeconds = SsoLoginRequestService.TTL.toSeconds();
        return switch (started) {
            case SsoLoginService.Start.Failure failure ->
                toLogin(failure.code(), failure.tenant(), SsoLoginService.safeNext(next));
            case SsoLoginService.Start.Redirect redirect ->
                ResponseEntity.status(HttpStatus.FOUND)
                        .cacheControl(CacheControl.noStore())
                        .location(URI.create(redirect.url()))
                        .header(HttpHeaders.SET_COOKIE, stateCookie(redirect.state(), cookieSeconds))
                        .build();
            case SsoLoginService.Start.ConfirmApp confirm ->
                ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .header(HttpHeaders.SET_COOKIE, stateCookie(confirm.state(), cookieSeconds))
                        // Nothing may frame the page (a hidden click on "Continue"),
                        // run script in it or take the provider URL along as referrer.
                        .header(
                                "Content-Security-Policy",
                                "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none';"
                                        + " base-uri 'none'; form-action 'none'")
                        .header("X-Frame-Options", "DENY")
                        .header("Referrer-Policy", "no-referrer")
                        .body(appConfirmPage(confirm));
        };
    }

    @GetMapping("/brain/_sso/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable("provider") String provider,
            @RequestParam(value = "code", required = false) @Nullable String code,
            @RequestParam(value = "state", required = false) @Nullable String state,
            @RequestParam(value = "error", required = false) @Nullable String error,
            HttpServletRequest request) {
        SsoLoginService.Callback result =
                ssoLoginService.callback(provider, code, state, error, readStateCookie(request));
        if (result instanceof SsoLoginService.Callback.AppHandoff handoff) {
            // Native app: no cookies in this (system) browser — the app's WebView
            // redeems the code and gets them in its own partition.
            return redirect(handoff.redirectUrl());
        }
        if (result instanceof SsoLoginService.Callback.Failure failure && failure.appRedirect() != null) {
            return redirect(SsoLoginService.withQuery(failure.appRedirect(), Map.of("error", failure.code())));
        }
        if (result instanceof SsoLoginService.Callback.Failure failure) {
            ResponseEntity<Void> toLogin = toLogin(failure.code(), failure.tenant(), null);
            return ResponseEntity.status(toLogin.getStatusCode())
                    .headers(toLogin.getHeaders())
                    .header(HttpHeaders.SET_COOKIE, stateCookie("", 0))
                    .build();
        }
        SsoLoginService.Callback.Success success = (SsoLoginService.Callback.Success) result;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(URI.create(success.next()))
                .header(HttpHeaders.SET_COOKIE, stateCookie("", 0));
        webUiCookieService.attachLoginCookies(
                builder,
                success.user(),
                success.issued().accessToken(),
                success.issued().accessExpiresAt(),
                success.issued().refreshToken(),
                success.issued().refreshExpiresAt(),
                true);
        return builder.build();
    }

    /**
     * The app's WebView redeems the handoff code with its verifier and gets the
     * same cookies (and body) as a password login.
     */
    @PostMapping("/brain/_sso/redeem")
    public ResponseEntity<AccessTokenResponse> redeem(
            @Valid @RequestBody SsoRedeemRequest body, HttpServletRequest request) {
        if (!withinBudget(Budget.REDEEM, request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        SsoLoginService.Redeemed redeemed =
                ssoLoginService.redeem(body.getCode(), body.getVerifier()).orElse(null);
        if (redeemed == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        LoginTokenIssuer.Issued issued = redeemed.issued();
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        webUiCookieService.attachLoginCookies(
                builder,
                redeemed.user(),
                issued.accessToken(),
                issued.accessExpiresAt(),
                issued.refreshToken(),
                issued.refreshExpiresAt(),
                true);
        AccessTokenResponse.AccessTokenResponseBuilder response = AccessTokenResponse.builder()
                .token(issued.accessToken())
                .expiresAtTimestamp(issued.accessExpiresAt().toEpochMilli());
        if (issued.refreshToken() != null && issued.refreshExpiresAt() != null) {
            response.refreshToken(issued.refreshToken())
                    .refreshTokenExpiresAtTimestamp(issued.refreshExpiresAt().toEpochMilli());
        }
        return builder.body(response.build());
    }

    /** Redirect that also clears the state cookie — the sign-in is over either way. */
    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(URI.create(url))
                .header(HttpHeaders.SET_COOKIE, stateCookie("", 0))
                .build();
    }

    private static ResponseEntity<SsoOptionsDto> options(Optional<String> provider, boolean domains) {
        SsoOptionsDto body = SsoOptionsDto.builder()
                .sso(provider.isPresent())
                .provider(provider.orElse(null))
                .domains(domains)
                .build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Void> toLogin(String code, @Nullable String tenant, @Nullable String next) {
        StringBuilder url = new StringBuilder("/login.html?sso_error=").append(enc(code));
        if (tenant != null) {
            url.append("&tenant=").append(enc(tenant));
        }
        if (next != null) {
            url.append("&next=").append(enc(next));
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(URI.create(url.toString()))
                .build();
    }

    private String stateCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                // Lax: the callback is a top-level GET navigation coming back
                // from the provider — Lax cookies ride along, Strict ones do not.
                .sameSite("Lax")
                .path(SsoLoginService.PATH_PREFIX)
                .maxAge(maxAgeSeconds)
                .build()
                .toString();
    }

    private static @Nullable String readStateCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (STATE_COOKIE.equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    boolean withinBudget(Budget kind, HttpServletRequest request) {
        BudgetKey key = new BudgetKey(kind, ClientAddresses.of(request));
        return budget.get(key, k -> new AtomicInteger()).incrementAndGet() <= requestsPerAddress;
    }

    /**
     * The confirmation in front of a native-app sign-in (sso-login.md §8.1):
     * who receives the login, and the warning to stop here unless the app is
     * waiting. Static HTML, no script; "Continue" is a plain link to the
     * provider, valid only in this browser (state cookie).
     */
    static String appConfirmPage(SsoLoginService.Start.ConfirmApp confirm) {
        String account = confirm.email() == null
                ? ""
                : "<p>Account: <strong>" + HtmlUtils.htmlEscape(confirm.email()) + "</strong></p>";
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Sign in to the Vancetope app</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;"
                + "color:#1f2937;background:#fafafa;line-height:1.5}h1{font-size:1.25rem}"
                + ".warn{border-left:3px solid #b45309;padding-left:.75rem}"
                + "a.go{display:inline-block;margin-top:1rem;padding:.6rem 1.1rem;border-radius:.375rem;"
                + "background:#1e3a5f;color:#fff;text-decoration:none}"
                + "@media (prefers-color-scheme:dark){body{color:#e5e7eb;background:#111827}"
                + "a.go{background:#3b5b85}}</style>"
                + "</head><body><h1>Sign in to the Vancetope app</h1>"
                + "<p>This sign-in will be handed to <strong>"
                + HtmlUtils.htmlEscape(confirm.receiver())
                + "</strong>.</p>"
                + account
                + "<p class=\"warn\">Continue only if you just chose &ldquo;Continue with Google&rdquo; in the Vancetope app"
                + " and it is waiting for you. If you opened this page from a link someone sent you, close it —"
                + " continuing would sign that person's app in as you.</p>"
                + "<a class=\"go\" id=\"continue\" href=\""
                + HtmlUtils.htmlEscape(confirm.url())
                + "\">Continue to Google</a>"
                + "</body></html>";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
