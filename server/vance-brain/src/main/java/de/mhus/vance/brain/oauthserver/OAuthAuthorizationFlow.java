package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.api.oauthserver.OAuthConsentRequestDto;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.oauthserver.OAuthSecrets;
import de.mhus.vance.shared.oauthserver.OAuthServerAuthRequestDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerAuthorizationService;
import de.mhus.vance.shared.oauthserver.OAuthServerClientService;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The authorization endpoint's logic, independent of HTTP: validate a
 * request, park it, carry it through login and consent, and turn it into a
 * code.
 *
 * <p><b>Two error channels, and which one is used matters.</b> Until the
 * client and its redirect URI are verified, an error is shown to the user
 * ({@link Step.ShowError}) and nothing is redirected — redirecting to an
 * unverified URI is an open redirect. After that, errors go back to the
 * client as {@code error=…} on its redirect URI ({@link Step.Redirect}), as
 * OAuth expects.
 *
 * <p>Every redirect to the client carries {@code iss} (RFC 9207) so the
 * client can tell this authorization server's response from a mix-up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthAuthorizationFlow {

    /** RFC 7636 §4.2: base64url SHA-256 — 43 characters. */
    private static final Pattern S256_CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final OAuthServerSettings settings;
    private final OAuthClientResolver clientResolver;
    private final OAuthServerClientService clientService;
    private final OAuthDoors doors;
    private final OAuthDoorGate gate;
    private final OAuthServerAuthorizationService authorizations;
    private final OAuthServerGrantService grants;
    private final AuditService auditService;

    /** What the authorization endpoint does next. */
    public sealed interface Step {

        /** Show an error page — the client could not be verified, never redirect. */
        record ShowError(String message) implements Step {}

        /** Send the browser somewhere (login, consent page, or back to the client). */
        record Redirect(String url) implements Step {}
    }

    /** The parameters of an authorization request, as received. */
    public record AuthorizeParams(
            @Nullable String responseType,
            @Nullable String clientId,
            @Nullable String redirectUri,
            @Nullable String state,
            @Nullable String codeChallenge,
            @Nullable String codeChallengeMethod,
            @Nullable String scope,
            @Nullable String resource) {}

    /**
     * Validate a fresh authorization request, park it, and continue as far as
     * the current login allows.
     *
     * @param loggedInUser the user of a valid full-account session on the request, or {@code null}
     */
    public Step begin(String tenant, AuthorizeParams params, @Nullable String loggedInUser) {
        if (!settings.enabled()) {
            return new Step.ShowError("The OAuth server is not configured on this Vancetope instance.");
        }
        if (blank(params.clientId())) {
            return new Step.ShowError("The request names no client (client_id is missing).");
        }
        ResolvedOAuthClient client;
        try {
            client = clientResolver.resolve(tenant, params.clientId());
        } catch (InvalidClientException e) {
            return new Step.ShowError("The client could not be accepted: " + e.getMessage());
        }
        String redirectUri = params.redirectUri();
        if (blank(redirectUri)) {
            return new Step.ShowError("The request names no redirect_uri.");
        }
        if (!client.allowsRedirect(redirectUri)) {
            log.debug(
                    "OAuth authorize rejected: redirect '{}' not registered for client '{}'",
                    redirectUri,
                    client.clientId());
            return new Step.ShowError("The redirect_uri is not registered for this client.");
        }

        // From here on the redirect URI is trusted: errors go back to the client.
        String state = params.state();
        if (!"code".equals(params.responseType())) {
            return errorRedirect(tenant, redirectUri, state, "unsupported_response_type", "only response_type=code");
        }
        if (!"S256".equals(params.codeChallengeMethod())
                || blank(params.codeChallenge())
                || !S256_CHALLENGE.matcher(params.codeChallenge()).matches()) {
            return errorRedirect(tenant, redirectUri, state, "invalid_request", "PKCE with S256 is required");
        }

        OAuthDoor door = null;
        if (!blank(params.resource())) {
            door = doors.fromResource(tenant, params.resource());
            if (door == null) {
                return errorRedirect(
                        tenant, redirectUri, state, "invalid_target", "resource is not an MCP door of this server");
            }
        }
        String scope = door == null ? OAuthDoor.ACCOUNT_SCOPE : door.scope();
        if (!scopeAcceptable(params.scope(), scope)) {
            return errorRedirect(
                    tenant, redirectUri, state, "invalid_scope", "scope must be '" + scope + "' for this resource");
        }
        if (door == null && !client.firstParty()) {
            // The account scope is a full-account credential. Only the built-in
            // clients may hold one; any other client must name a door.
            return errorRedirect(
                    tenant, redirectUri, state, "invalid_target", "a resource (MCP door) is required for this client");
        }

        OAuthServerAuthRequestDocument request = authorizations.createRequest(OAuthServerAuthRequestDocument.builder()
                .tenantId(tenant)
                .clientId(client.clientId())
                .clientName(client.clientName())
                .clientKind(client.kind().wire())
                .clientUri(client.clientUri())
                .redirectUri(redirectUri)
                .state(state)
                .codeChallenge(params.codeChallenge())
                .resource(door == null ? null : doors.resourceUrl(tenant, door))
                .scope(scope)
                .scopeProfiles(door == null ? List.of() : door.scopeProfiles())
                .projectId(door == null ? null : door.project())
                .build());
        if (client.kind() == OAuthClientKind.DCR) {
            clientService.touch(client.clientId());
        }
        return proceed(tenant, request, loggedInUser);
    }

    /** Continue a parked request — after login, or on a fresh visit. */
    public Step resume(String tenant, String requestId, @Nullable String loggedInUser) {
        OAuthServerAuthRequestDocument request =
                authorizations.findRequest(tenant, requestId).orElse(null);
        if (request == null) {
            return new Step.ShowError("This sign-in request has expired. Start again from the application.");
        }
        return proceed(tenant, request, loggedInUser);
    }

    private Step proceed(String tenant, OAuthServerAuthRequestDocument request, @Nullable String loggedInUser) {
        if (loggedInUser == null) {
            String returnPath = "/brain/" + tenant + "/oauth/authorize/resume/" + request.getRequestId();
            return new Step.Redirect(
                    settings.baseUrl() + "/login.html?tenant=" + enc(tenant) + "&next=" + enc(returnPath));
        }
        OAuthServerAuthRequestDocument bound = authorizations
                .bindUser(tenant, request.getRequestId(), loggedInUser)
                .orElse(null);
        if (bound == null) {
            return new Step.ShowError("This sign-in request belongs to another user.");
        }
        OAuthDoor door = doorOf(tenant, bound);
        boolean firstParty = OAuthClientKind.FIRST_PARTY.wire().equals(bound.getClientKind());
        if (firstParty) {
            return approve(tenant, bound, loggedInUser, door);
        }
        // A live grant this user already gave this client for this door: no
        // second consent — but the door's gate is asked again, the config may
        // have changed since.
        Optional<OAuthServerGrantDocument> reusable =
                grants.findReusable(tenant, loggedInUser, bound.getClientId(), bound.getResource());
        if (reusable.isPresent()) {
            return approve(tenant, bound, loggedInUser, door);
        }
        return new Step.Redirect(settings.baseUrl() + "/oauth-consent.html?tenant=" + enc(tenant) + "&request="
                + enc(bound.getRequestId()));
    }

    /** What the consent page shows; binds the request to the viewing user. */
    public Optional<OAuthConsentRequestDto> consentView(String tenant, String requestId, String user) {
        return authorizations
                .bindUser(tenant, requestId, user)
                .map(r -> OAuthConsentRequestDto.builder()
                        .requestId(r.getRequestId())
                        .consentTicket(r.getConsentTicket())
                        .tenantId(tenant)
                        .userId(user)
                        .clientId(r.getClientId())
                        .clientName(r.getClientName())
                        .clientKind(r.getClientKind())
                        .clientUri(r.getClientUri())
                        .redirectUri(r.getRedirectUri())
                        .redirectHost(RedirectUris.host(r.getRedirectUri()))
                        .loopbackRedirect(RedirectUris.isLoopback(r.getRedirectUri()))
                        .scope(r.getScope())
                        .resource(r.getResource())
                        .projectId(r.getProjectId())
                        .build());
    }

    /**
     * Apply the user's decision. Returns the URL the browser must go to, or
     * empty when the request, the user or the ticket do not line up.
     */
    public Optional<String> decide(
            String tenant, String requestId, String consentTicket, boolean approve, String user) {
        OAuthServerAuthRequestDocument request =
                authorizations.findRequest(tenant, requestId).orElse(null);
        if (request == null
                || !user.equals(request.getUserId())
                || request.getConsentTicket() == null
                || !OAuthSecrets.sameHash(request.getConsentTicket(), consentTicket)) {
            return Optional.empty();
        }
        if (!approve) {
            authorizations.deleteRequest(requestId);
            log.info("OAuth consent declined tenant='{}' user='{}' client='{}'", tenant, user, request.getClientId());
            return Optional.of(errorUrl(
                    tenant, request.getRedirectUri(), request.getState(), "access_denied", "the user declined"));
        }
        Step step = approve(tenant, request, user, doorOf(tenant, request));
        return step instanceof Step.Redirect redirect ? Optional.of(redirect.url()) : Optional.empty();
    }

    /** Create or reuse the grant, issue a code, send the browser back to the client. */
    private Step approve(String tenant, OAuthServerAuthRequestDocument request, String user, @Nullable OAuthDoor door) {
        String denial = gate.denial(tenant, user, door);
        if (denial != null) {
            authorizations.deleteRequest(request.getRequestId());
            log.info(
                    "OAuth grant refused tenant='{}' user='{}' client='{}' scope='{}': {}",
                    tenant,
                    user,
                    request.getClientId(),
                    request.getScope(),
                    denial);
            return errorRedirect(tenant, request.getRedirectUri(), request.getState(), "access_denied", denial);
        }
        OAuthServerGrantDocument grant = grants.findReusable(tenant, user, request.getClientId(), request.getResource())
                .orElseGet(() -> {
                    OAuthServerGrantDocument created = grants.create(
                            tenant,
                            user,
                            request.getClientId(),
                            request.getClientName(),
                            request.getResource(),
                            request.getScope(),
                            request.getScopeProfiles(),
                            request.getProjectId(),
                            settings.grantMaxLifetime());
                    auditService.authOAuthGrantIssued(
                            tenant,
                            user,
                            request.getClientId(),
                            request.getScope(),
                            request.getProjectId(),
                            created.getGrantId());
                    return created;
                });
        String code = authorizations.issueCode(
                tenant,
                user,
                grant.getGrantId(),
                request.getClientId(),
                request.getRedirectUri(),
                request.getCodeChallenge(),
                request.getResource());
        authorizations.deleteRequest(request.getRequestId());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        if (request.getState() != null) {
            params.put("state", request.getState());
        }
        params.put("iss", settings.issuer(tenant));
        return new Step.Redirect(appendQuery(request.getRedirectUri(), params));
    }

    private @Nullable OAuthDoor doorOf(String tenant, OAuthServerAuthRequestDocument request) {
        return request.getResource() == null ? null : doors.fromResource(tenant, request.getResource());
    }

    private Step errorRedirect(
            String tenant, String redirectUri, @Nullable String state, String error, String description) {
        return new Step.Redirect(errorUrl(tenant, redirectUri, state, error, description));
    }

    private String errorUrl(
            String tenant, String redirectUri, @Nullable String state, String error, String description) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", error);
        params.put("error_description", description);
        if (state != null) {
            params.put("state", state);
        }
        params.put("iss", settings.issuer(tenant));
        return appendQuery(redirectUri, params);
    }

    /**
     * The requested scope must be the door's, or absent. Extra tokens are
     * refused rather than dropped, except {@code offline_access}, which some
     * clients add to ask for a refresh token — they get one anyway.
     */
    static boolean scopeAcceptable(@Nullable String requested, String doorScope) {
        if (requested == null || requested.isBlank()) {
            return true;
        }
        return Arrays.stream(requested.trim().split("\\s+"))
                .allMatch(s -> s.equals(doorScope) || s.equals("offline_access"));
    }

    static String appendQuery(String uri, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(uri);
        char sep = uri.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sep).append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
