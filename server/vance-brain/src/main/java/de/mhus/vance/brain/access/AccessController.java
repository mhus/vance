package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.access.RefreshTokenResponse;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint that mints JWTs after verifying username + password.
 *
 * <p>{@code POST /brain/{tenant}/access/{username}} with body
 * {@code {"password": "..."}} — returns a fresh token on success.
 *
 * <p>The path is exempted from {@link BrainAccessFilter} — callers have no
 * token yet when hitting this endpoint.
 *
 * <p>Auth rules:
 * <ul>
 *   <li>User must exist in {@code tenant}</li>
 *   <li>{@link UserDocument#getStatus()} must be {@link UserStatus#ACTIVE}</li>
 *   <li>User must have a {@code passwordHash} set (OAuth-only users can't
 *       log in here)</li>
 *   <li>{@code password} from the body must verify against the stored hash</li>
 * </ul>
 * Any failure returns {@code 401 Unauthorized} with no body — the specific
 * reason is only logged at DEBUG to avoid user-enumeration via response
 * differences.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AccessController {

    private static final Duration TOKEN_LIFETIME = Duration.ofHours(24);

    private final JwtService jwtService;
    private final UserService userService;
    private final PasswordService passwordService;
    private final HomeBootstrapService homeBootstrapService;

    @PostMapping("/brain/{tenant}/access/{username}")
    public ResponseEntity<AccessTokenResponse> createToken(
            @PathVariable("tenant") String tenant,
            @PathVariable("username") String username,
            @Valid @RequestBody AccessTokenRequest request) {

        Optional<UserDocument> userOpt = userService.findByTenantAndName(tenant, username);
        if (userOpt.isEmpty()) {
            log.debug("Login rejected: unknown user tenant='{}' name='{}'", tenant, username);
            return unauthorized();
        }
        UserDocument user = userOpt.get();

        if (user.getStatus() != UserStatus.ACTIVE) {
            log.debug("Login rejected: status={} tenant='{}' name='{}'", user.getStatus(), tenant, username);
            return unauthorized();
        }

        String hash = user.getPasswordHash();
        if (hash == null) {
            log.debug("Login rejected: no password hash tenant='{}' name='{}'", tenant, username);
            return unauthorized();
        }

        if (!passwordService.verify(request.getPassword(), hash)) {
            log.debug("Login rejected: bad password tenant='{}' name='{}'", tenant, username);
            return unauthorized();
        }

        // First-login Hub bootstrap: ensures the per-user vance-<login>
        // SYSTEM project (and the tenant-level Home group) exist before
        // the client opens a hub session. Idempotent — a no-op on
        // every subsequent login. Failure logs and re-throws; we'd
        // rather block the login than mint a token for a user whose
        // hub can't be opened.
        homeBootstrapService.ensureHome(tenant, username);

        Instant expiresAt = Instant.now().plus(TOKEN_LIFETIME);
        String token = jwtService.createToken(tenant, username, expiresAt);

        log.info("Issued JWT tenant='{}' user='{}' expiresAt={}", tenant, username, expiresAt);
        return ResponseEntity.ok(AccessTokenResponse.builder()
                .token(token)
                .expiresAtTimestamp(expiresAt.toEpochMilli())
                .build());
    }

    /**
     * Re-mint a JWT in exchange for a still-valid one.
     *
     * <p>{@code POST /brain/{tenant}/refresh} — caller authenticates with their
     * current bearer token (validated by {@link BrainAccessFilter}). On success
     * a fresh token with a new {@link #TOKEN_LIFETIME} is issued for the same
     * user/tenant.
     *
     * <p>Re-checks that the user is still {@link UserStatus#ACTIVE} — a token
     * issued before the user was deactivated must not be refreshable.
     */
    @PostMapping("/brain/{tenant}/refresh")
    public ResponseEntity<RefreshTokenResponse> refreshToken(
            @PathVariable("tenant") String tenant,
            HttpServletRequest request) {

        String username = (String) request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (username == null) {
            // The filter would have rejected the request before reaching here, so
            // this branch is defensive only.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<UserDocument> userOpt = userService.findByTenantAndName(tenant, username);
        if (userOpt.isEmpty() || userOpt.get().getStatus() != UserStatus.ACTIVE) {
            log.debug("Refresh rejected: user inactive or missing tenant='{}' name='{}'", tenant, username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Instant expiresAt = Instant.now().plus(TOKEN_LIFETIME);
        String token = jwtService.createToken(tenant, username, expiresAt);

        log.info("Refreshed JWT tenant='{}' user='{}' expiresAt={}", tenant, username, expiresAt);
        return ResponseEntity.ok(RefreshTokenResponse.builder()
                .token(token)
                .expiresAtTimestamp(expiresAt.toEpochMilli())
                .build());
    }

    private static ResponseEntity<AccessTokenResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
