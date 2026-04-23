package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
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

        Instant expiresAt = Instant.now().plus(TOKEN_LIFETIME);
        String token = jwtService.createToken(tenant, username, expiresAt);

        log.info("Issued JWT tenant='{}' user='{}' expiresAt={}", tenant, username, expiresAt);
        return ResponseEntity.ok(AccessTokenResponse.builder()
                .token(token)
                .expiresAtTimestamp(expiresAt.toEpochMilli())
                .build());
    }

    private static ResponseEntity<AccessTokenResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
