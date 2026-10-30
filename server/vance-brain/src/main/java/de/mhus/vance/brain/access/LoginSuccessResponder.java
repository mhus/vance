package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.user.UserDocument;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * What every successful sign-in at {@code /brain/{tenant}/access/…} answers:
 * the tokens in the body, the web-UI cookies if asked for, and the audit entry.
 * Shared by the password login and the e-mail code login, so both end
 * identically.
 */
@Component
public class LoginSuccessResponder {

    private final LoginTokenIssuer loginTokenIssuer;
    private final AuditService auditService;
    private final WebUiCookieService webUiCookieService;

    public LoginSuccessResponder(
            LoginTokenIssuer loginTokenIssuer, AuditService auditService, WebUiCookieService webUiCookieService) {
        this.loginTokenIssuer = loginTokenIssuer;
        this.auditService = auditService;
        this.webUiCookieService = webUiCookieService;
    }

    public ResponseEntity<AccessTokenResponse> respond(
            UserDocument user,
            String tenant,
            String username,
            boolean withRefreshToken,
            boolean withCookies,
            @Nullable Instant sessionExpiresAt) {
        LoginTokenIssuer.Issued issued = loginTokenIssuer.issue(tenant, username, withRefreshToken, sessionExpiresAt);

        AccessTokenResponse.AccessTokenResponseBuilder responseBuilder = AccessTokenResponse.builder()
                .token(issued.accessToken())
                .expiresAtTimestamp(issued.accessExpiresAt().toEpochMilli());
        if (issued.refreshToken() != null && issued.refreshExpiresAt() != null) {
            responseBuilder
                    .refreshToken(issued.refreshToken())
                    .refreshTokenExpiresAtTimestamp(issued.refreshExpiresAt().toEpochMilli());
        }
        auditService.authLoginSuccess(tenant, username);

        ResponseEntity.BodyBuilder responseEntityBuilder = ResponseEntity.ok();
        if (withCookies) {
            webUiCookieService.attachLoginCookies(
                    responseEntityBuilder,
                    user,
                    issued.accessToken(),
                    issued.accessExpiresAt(),
                    issued.refreshToken(),
                    issued.refreshExpiresAt());
        }
        return responseEntityBuilder.body(responseBuilder.build());
    }
}
