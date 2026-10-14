package de.mhus.vance.brain.oauthserver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token and revocation endpoints — {@code application/x-www-form-urlencoded}
 * in, OAuth JSON out, {@code Cache-Control: no-store} on every answer.
 *
 * <p>A token request that repeats a parameter is {@code invalid_request}
 * (RFC 6749 §3.2: "Request and response parameters MUST NOT be included more
 * than once") — otherwise which of two {@code client_id}s or
 * {@code redirect_uri}s is checked would depend on parsing order.
 */
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OAuthTokenController {

    private final OAuthTokenService tokenService;

    @PostMapping(
            value = "/brain/{tenant}/oauth/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @PathVariable("tenant") String tenant, @RequestParam MultiValueMap<String, String> form) {
        try {
            requireSingleValues(form);
            String grantType = form.getFirst("grant_type");
            Map<String, Object> body;
            if ("authorization_code".equals(grantType)) {
                body = tokenService.redeemCode(
                        tenant,
                        form.getFirst("code"),
                        form.getFirst("redirect_uri"),
                        form.getFirst("client_id"),
                        form.getFirst("code_verifier"),
                        form.getFirst("resource"));
            } else if ("refresh_token".equals(grantType)) {
                body = tokenService.refresh(
                        tenant, form.getFirst("refresh_token"), form.getFirst("client_id"), form.getFirst("resource"));
            } else {
                throw new OAuthTokenService.OAuthTokenError(
                        HttpStatus.BAD_REQUEST,
                        "unsupported_grant_type",
                        "grant_type must be authorization_code or refresh_token");
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .body(body);
        } catch (OAuthTokenService.OAuthTokenError e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.error());
            error.put("error_description", e.getMessage());
            return ResponseEntity.status(e.status())
                    .cacheControl(CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .body(error);
        }
    }

    private static void requireSingleValues(MultiValueMap<String, String> form) {
        for (Map.Entry<String, List<String>> e : form.entrySet()) {
            if (e.getValue() != null && e.getValue().size() > 1) {
                throw new OAuthTokenService.OAuthTokenError(
                        HttpStatus.BAD_REQUEST, "invalid_request", "parameter '" + e.getKey() + "' is repeated");
            }
        }
    }

    /** RFC 7009 — always 200, whether or not the token was known. */
    @PostMapping(value = "/brain/{tenant}/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> revoke(
            @PathVariable("tenant") String tenant, @RequestParam MultiValueMap<String, String> form) {
        tokenService.revoke(tenant, form.getFirst("token"), form.getFirst("client_id"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
