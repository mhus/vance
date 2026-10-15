package de.mhus.vance.brain.sso;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.api.sso.SsoOptionsDto;
import de.mhus.vance.brain.access.WebUiCookieService;
import de.mhus.vance.brain.oauthserver.ClientAddresses;
import de.mhus.vance.shared.sso.SsoLoginRequestService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The open SSO endpoints under {@code /brain/_sso/} — no tenant in the path,
 * because at sign-in the tenant is not known yet. Exempted from the access
 * filter; budgeted per client address instead.
 *
 * <ul>
 *   <li>{@code GET discover?email=} — does this address sign in via SSO?</li>
 *   <li>{@code GET options?tenant=} — does this tenant's login page offer a
 *       private Google account?</li>
 *   <li>{@code GET start?email=&tenant=&next=} — off to the identity provider.</li>
 *   <li>{@code GET {provider}/callback} — back from it; on success the same
 *       cookies as the password login, then on to {@code next}.</li>
 * </ul>
 *
 * <p>Failures redirect to the login page with a coarse {@code sso_error} —
 * the reason is in the audit log, not in the URL.
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
    private final Cache<String, AtomicInteger> budget;

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
        if (!withinBudget(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return options(ssoLoginService.providerForEmail(email), false);
    }

    @GetMapping("/brain/_sso/options")
    public ResponseEntity<SsoOptionsDto> tenantOptions(
            @RequestParam(value = "tenant", required = false) @Nullable String tenant, HttpServletRequest request) {
        if (!withinBudget(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return options(
                tenant == null ? Optional.empty() : ssoLoginService.consumerProvider(tenant),
                ssoLoginService.domainSignInConfigured());
    }

    @GetMapping("/brain/_sso/start")
    public ResponseEntity<Void> start(
            @RequestParam(value = "email", required = false) @Nullable String email,
            @RequestParam(value = "tenant", required = false) @Nullable String tenant,
            @RequestParam(value = "next", required = false) @Nullable String next,
            HttpServletRequest request) {
        if (!withinBudget(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        SsoLoginService.Start started = ssoLoginService.start(email, tenant, next);
        if (started instanceof SsoLoginService.Start.Failure failure) {
            return toLogin(failure.code(), failure.tenant(), SsoLoginService.safeNext(next));
        }
        SsoLoginService.Start.Redirect redirect = (SsoLoginService.Start.Redirect) started;
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(URI.create(redirect.url()))
                .header(
                        HttpHeaders.SET_COOKIE,
                        stateCookie(
                                redirect.state(),
                                // The cookie lives exactly as long as the pending request.
                                SsoLoginRequestService.TTL.toSeconds()))
                .build();
    }

    @GetMapping("/brain/_sso/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable("provider") String provider,
            @RequestParam(value = "code", required = false) @Nullable String code,
            @RequestParam(value = "state", required = false) @Nullable String state,
            @RequestParam(value = "error", required = false) @Nullable String error,
            HttpServletRequest request) {
        if (!withinBudget(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        SsoLoginService.Callback result =
                ssoLoginService.callback(provider, code, state, error, readStateCookie(request));
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

    private boolean withinBudget(HttpServletRequest request) {
        String address = ClientAddresses.of(request);
        return budget.get(address, k -> new AtomicInteger()).incrementAndGet() <= requestsPerAddress;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
