package de.mhus.vance.brain.oauthserver;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.shared.oauthserver.OAuthServerClientDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerClientService;
import de.mhus.vance.shared.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dynamic Client Registration (RFC 7591) — {@code POST /brain/{tenant}/oauth/register}.
 *
 * <p>Deprecated in the MCP authorization spec in favour of Client ID Metadata
 * Documents, kept because MCP clients still use it. Only public clients:
 * {@code token_endpoint_auth_method} must be {@code none} (or absent), no
 * secret is issued. Redirect URIs must be HTTPS or loopback.
 *
 * <p>The endpoint is open by nature, so it is fenced: an hourly budget per
 * client address ({@link #REGISTRATIONS_PER_ADDRESS_PER_HOUR}) under an
 * hourly ceiling per tenant ({@link #REGISTRATIONS_PER_HOUR}), both on this
 * pod; size limits on every field; a {@code client_name} cleaned of
 * invisible and direction-changing characters ({@link OAuthClientNames});
 * no redirect URI on the server's own origin; and unused registrations are
 * cleaned up ({@link OAuthClientCleanup}). A registration grants nothing —
 * every grant still needs a logged-in user's consent.
 *
 * <p>The client address comes from {@link ClientAddresses}: the socket peer,
 * followed back through {@code X-Forwarded-For} only across our own
 * (non-public) proxies, so a client cannot pick its own budget key by
 * sending the header.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class OAuthRegistrationController {

    static final int MAX_REDIRECT_URIS = 10;
    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_URI_LENGTH = 2000;
    static final int REGISTRATIONS_PER_HOUR = 60;
    static final int REGISTRATIONS_PER_ADDRESS_PER_HOUR = 5;
    static final int MAX_TRACKED_KEYS = 10_000;
    static final Duration BUDGET_WINDOW = Duration.ofHours(1);

    private final OAuthServerSettings settings;
    private final OAuthServerClientService clientService;
    private final TenantService tenantService;

    /**
     * {@code tenant -> count} — the hourly ceiling per pod. An entry expires
     * one window after it was created, so each entry is a fixed window.
     */
    private final Cache<String, AtomicInteger> tenantBudget = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterWrite(BUDGET_WINDOW)
            .build();

    /**
     * {@code client address -> count} — the hourly budget per
     * address, bounded; evicting an entry early only resets one address's
     * budget, the tenant ceiling still holds.
     */
    private final Cache<String, AtomicInteger> addressBudget = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterWrite(BUDGET_WINDOW)
            .build();

    @PostMapping(
            value = "/brain/{tenant}/oauth/register",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(
            @PathVariable("tenant") String tenant, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!settings.enabled()
                || !settings.registrationEnabled()
                || tenantService.findByName(tenant).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "invalid_request", "registration is not available");
        }
        String address = ClientAddresses.of(request);
        if (!consume(addressBudget, address, REGISTRATIONS_PER_ADDRESS_PER_HOUR)) {
            log.info("OAuth registration budget exhausted for address '{}' (tenant '{}')", address, tenant);
            return error(HttpStatus.TOO_MANY_REQUESTS, "invalid_request", "too many registrations, try later");
        }
        if (!consume(tenantBudget, tenant, REGISTRATIONS_PER_HOUR)) {
            log.warn("OAuth registration budget exhausted for tenant '{}'", tenant);
            return error(HttpStatus.TOO_MANY_REQUESTS, "invalid_request", "too many registrations, try later");
        }

        Object authMethod = body.get("token_endpoint_auth_method");
        if (authMethod != null && !"none".equals(authMethod)) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "invalid_client_metadata",
                    "only public clients are supported (token_endpoint_auth_method none)");
        }
        if (!subsetOf(body.get("grant_types"), List.of("authorization_code", "refresh_token"))) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "invalid_client_metadata",
                    "grant_types may only contain authorization_code and refresh_token");
        }
        if (!subsetOf(body.get("response_types"), List.of("code"))) {
            return error(HttpStatus.BAD_REQUEST, "invalid_client_metadata", "response_types may only contain code");
        }
        List<String> redirectUris = new ArrayList<>();
        if (!(body.get("redirect_uris") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_REDIRECT_URIS) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "invalid_redirect_uri",
                    "redirect_uris must list 1 to " + MAX_REDIRECT_URIS + " URIs");
        }
        String ownOrigin = settings.ownOrigin();
        for (Object item : raw) {
            if (!(item instanceof String uri) || !RedirectUris.isAcceptable(uri, ownOrigin)) {
                return error(
                        HttpStatus.BAD_REQUEST,
                        "invalid_redirect_uri",
                        "redirect URIs must be https or http to a loopback address, without fragment,"
                                + " and not on this server's own origin");
            }
            redirectUris.add(uri);
        }
        String name = body.get("client_name") instanceof String rawName
                ? OAuthClientNames.sanitize(rawName, MAX_NAME_LENGTH)
                : null;
        String clientUri = httpsOrNull(body.get("client_uri"));
        String logoUri = httpsOrNull(body.get("logo_uri"));

        OAuthServerClientDocument client = clientService.register(
                tenant, name == null ? "Unnamed client" : name, clientUri, logoUri, redirectUris);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", client.getClientId());
        out.put(
                "client_id_issued_at",
                client.getCreatedAt() == null ? 0 : client.getCreatedAt().getEpochSecond());
        out.put("client_name", client.getClientName());
        if (clientUri != null) out.put("client_uri", clientUri);
        if (logoUri != null) out.put("logo_uri", logoUri);
        out.put("redirect_uris", client.getRedirectUris());
        out.put("grant_types", List.of("authorization_code", "refresh_token"));
        out.put("response_types", List.of("code"));
        out.put("token_endpoint_auth_method", "none");
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(out);
    }

    private static boolean consume(Cache<String, AtomicInteger> budget, String key, int limit) {
        return budget.get(key, k -> new AtomicInteger()).incrementAndGet() <= limit;
    }

    private static boolean subsetOf(@Nullable Object raw, List<String> allowed) {
        if (raw == null) {
            return true;
        }
        if (!(raw instanceof List<?> list)) {
            return false;
        }
        return list.stream().allMatch(allowed::contains);
    }

    private static @Nullable String text(@Nullable Object raw, int max) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static @Nullable String httpsOrNull(@Nullable Object raw) {
        String value = text(raw, MAX_URI_LENGTH);
        return value != null && value.regionMatches(true, 0, "https://", 0, 8) ? value : null;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String error, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
