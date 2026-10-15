package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.api.oauthserver.OAuthConsentDecisionRequest;
import de.mhus.vance.api.oauthserver.OAuthConsentDecisionResponse;
import de.mhus.vance.api.oauthserver.OAuthConsentRequestDto;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/**
 * Browser-facing half of the authorization code flow.
 *
 * <ul>
 *   <li>{@code GET /oauth/authorize} — the authorization endpoint. Open at the
 *       filter; reads the Web-UI session cookie itself when present.</li>
 *   <li>{@code GET /oauth/authorize/resume/{id}} — where the login page sends
 *       the browser back to after a successful login.</li>
 *   <li>{@code GET /oauth/authorize/request/{id}} and
 *       {@code POST /oauth/authorize/consent} — the consent page's REST
 *       calls; these need the logged-in user.</li>
 * </ul>
 * The logic lives in {@link OAuthAuthorizationFlow}.
 */
@RestController
@RequiredArgsConstructor
public class OAuthAuthorizeController {

    private final OAuthAuthorizationFlow flow;
    private final TenantService tenantService;
    private final OAuthServerSettings settings;

    @GetMapping("/brain/{tenant}/oauth/authorize")
    public ResponseEntity<String> authorize(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "response_type", required = false) @Nullable String responseType,
            @RequestParam(value = "client_id", required = false) @Nullable String clientId,
            @RequestParam(value = "redirect_uri", required = false) @Nullable String redirectUri,
            @RequestParam(value = "state", required = false) @Nullable String state,
            @RequestParam(value = "code_challenge", required = false) @Nullable String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) @Nullable String codeChallengeMethod,
            @RequestParam(value = "scope", required = false) @Nullable String scope,
            @RequestParam(value = "resource", required = false) @Nullable String resource,
            HttpServletRequest request) {
        // Before the tenant lookup: a disabled server must not answer which
        // tenants exist.
        if (!settings.enabled()) {
            return errorPage(OAuthAuthorizationFlow.SERVER_DISABLED);
        }
        if (tenantService.findByName(tenant).isEmpty()) {
            return errorPage("Unknown tenant.");
        }
        OAuthAuthorizationFlow.AuthorizeParams params = new OAuthAuthorizationFlow.AuthorizeParams(
                responseType, clientId, redirectUri, state, codeChallenge, codeChallengeMethod, scope, resource);
        return render(flow.begin(tenant, params, loggedInUser(request), sessionEnd(request)));
    }

    @GetMapping("/brain/{tenant}/oauth/authorize/resume/{requestId}")
    public ResponseEntity<String> resume(
            @PathVariable("tenant") String tenant,
            @PathVariable("requestId") String requestId,
            HttpServletRequest request) {
        return render(flow.resume(tenant, requestId, loggedInUser(request), sessionEnd(request)));
    }

    @GetMapping(
            value = "/brain/{tenant}/oauth/authorize/request/{requestId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OAuthConsentRequestDto> consentRequest(
            @PathVariable("tenant") String tenant,
            @PathVariable("requestId") String requestId,
            HttpServletRequest request) {
        String user = requireLoggedInUser(request);
        return flow.consentView(tenant, requestId, user)
                .map(dto ->
                        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dto))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This sign-in request has expired or belongs to another user."));
    }

    @PostMapping(value = "/brain/{tenant}/oauth/authorize/consent", produces = MediaType.APPLICATION_JSON_VALUE)
    public OAuthConsentDecisionResponse consent(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody OAuthConsentDecisionRequest decision,
            HttpServletRequest request) {
        String user = requireLoggedInUser(request);
        String url = flow.decide(
                        tenant,
                        decision.getRequestId(),
                        decision.getConsentTicket(),
                        decision.isApprove(),
                        user,
                        sessionEnd(request))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This sign-in request has expired or belongs to another user."));
        return OAuthConsentDecisionResponse.builder().redirectUrl(url).build();
    }

    /**
     * The user of a full-account session on this request — the Web-UI cookie
     * or a login bearer — or {@code null}. A narrowed credential (integration
     * token, door-bound OAuth token) is not a login and cannot consent.
     */
    static @Nullable String loggedInUser(HttpServletRequest request) {
        if (request.getAttribute(AccessFilterBase.ATTR_CLAIMS) instanceof VanceJwtClaims claims
                && claims.isFullAccountCredential()) {
            return claims.username();
        }
        return null;
    }

    /**
     * Hard end of the login session on this request ({@code sxp} — SSO
     * logins), or {@code null}. A grant created from the session inherits it.
     */
    static @Nullable Instant sessionEnd(HttpServletRequest request) {
        if (request.getAttribute(AccessFilterBase.ATTR_CLAIMS) instanceof VanceJwtClaims claims
                && claims.isFullAccountCredential()) {
            return claims.sessionExpiresAt();
        }
        return null;
    }

    private static String requireLoggedInUser(HttpServletRequest request) {
        String user = loggedInUser(request);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A login session is required.");
        }
        return user;
    }

    private static ResponseEntity<String> render(OAuthAuthorizationFlow.Step step) {
        return switch (step) {
            case OAuthAuthorizationFlow.Step.Redirect redirect ->
                ResponseEntity.status(HttpStatus.FOUND)
                        .cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.LOCATION, URI.create(redirect.url()).toString())
                        .build();
            case OAuthAuthorizationFlow.Step.ShowError error -> errorPage(error.message());
        };
    }

    /**
     * A plain error page — shown, never redirected, because at this point the
     * client's redirect URI is not trusted.
     */
    private static ResponseEntity<String> errorPage(String message) {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Sign-in failed</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;"
                + "color:#222;background:#fafafa}h1{font-size:1.25rem}p{line-height:1.5}"
                + "@media (prefers-color-scheme:dark){body{color:#ddd;background:#1b1b1b}}</style>"
                + "</head><body><h1>Sign-in failed</h1><p>"
                + HtmlUtils.htmlEscape(message)
                + "</p></body></html>";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
