package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.access.EmailTokenStartResponse;
import de.mhus.vance.api.access.EmailTokenVerifyRequest;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.user.EmailLoginPurpose;
import de.mhus.vance.shared.user.EmailLoginService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in with a code sent by e-mail, the alternative to the password.
 *
 * <ul>
 *   <li>{@code POST /brain/{tenant}/access/{username}/email-token} — mails a
 *       code to the address on file and answers with a {@code transactionId}.
 *       The answer is the same for every username.</li>
 *   <li>{@code POST /brain/{tenant}/access/{username}/email-token/verify} —
 *       trades {@code transactionId} + code for the same tokens and cookies the
 *       password login gives.</li>
 * </ul>
 *
 * <p>Both are open (the caller has no token yet) and answer 404 unless
 * {@code vance.auth.email-token.enabled} is set. Each is budgeted per client
 * address; a wrong code is a uniform 401, the reason only in the audit log.
 */
@Slf4j
@RestController
public class EmailTokenController {

    private static final Duration BUDGET_WINDOW = Duration.ofMinutes(10);

    private final EmailLoginService emailLoginService;
    private final UserService userService;
    private final LoginModes loginModes;
    private final LoginSuccessResponder loginSuccessResponder;
    private final AuditService auditService;
    private final AddressBudget startBudget;
    private final AddressBudget verifyBudget;

    public EmailTokenController(
            EmailLoginService emailLoginService,
            UserService userService,
            LoginModes loginModes,
            LoginSuccessResponder loginSuccessResponder,
            AuditService auditService,
            @Value("${vance.auth.email-token.starts-per-address:20}") int startsPerAddress,
            @Value("${vance.auth.email-token.verifies-per-address:60}") int verifiesPerAddress) {
        this.emailLoginService = emailLoginService;
        this.userService = userService;
        this.loginModes = loginModes;
        this.loginSuccessResponder = loginSuccessResponder;
        this.auditService = auditService;
        this.startBudget = new AddressBudget(startsPerAddress, BUDGET_WINDOW);
        this.verifyBudget = new AddressBudget(verifiesPerAddress, BUDGET_WINDOW);
    }

    @PostMapping("/brain/{tenant}/access/{username}/email-token")
    public ResponseEntity<EmailTokenStartResponse> start(
            @PathVariable("tenant") String tenant,
            @PathVariable("username") String username,
            HttpServletRequest request) {
        if (!loginModes.emailTokenEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (!startBudget.allow(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        EmailLoginService.Started started = emailLoginService.startLogin(tenant, username);
        return ResponseEntity.ok(EmailTokenStartResponse.builder()
                .transactionId(started.transactionId())
                .expiresAtTimestamp(started.expiresAt().toEpochMilli())
                .build());
    }

    @PostMapping("/brain/{tenant}/access/{username}/email-token/verify")
    public ResponseEntity<AccessTokenResponse> verify(
            @PathVariable("tenant") String tenant,
            @PathVariable("username") String username,
            @Valid @RequestBody EmailTokenVerifyRequest body,
            HttpServletRequest request) {
        if (!loginModes.emailTokenEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (!verifyBudget.allow(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        EmailLoginService.Verification verification = emailLoginService.verify(
                tenant, username, EmailLoginPurpose.LOGIN, body.getTransactionId(), body.getToken());
        if (!verification.success()) {
            return reject(tenant, username, "email-token-invalid");
        }
        // The code was good, but the account may have changed since it was issued.
        Optional<UserDocument> found = userService.findByTenantAndName(tenant, username);
        if (found.isEmpty() || !mayLogIn(found.get())) {
            return reject(tenant, username, "user-not-allowed");
        }
        UserDocument user = found.get();
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            userService.resetLoginFailures(tenant, username);
        }
        return loginSuccessResponder.respond(
                user, tenant, username, body.isRequestRefreshToken(), body.isRequestCookies(), null);
    }

    private static boolean mayLogIn(UserDocument user) {
        return user.getStatus() == UserStatus.ACTIVE
                && user.isLoginEnabled()
                && !user.isServiceAccount()
                && user.getExternalIdentity() == null;
    }

    private ResponseEntity<AccessTokenResponse> reject(String tenant, String username, String reason) {
        log.debug("E-mail login rejected: {} tenant='{}' name='{}'", reason, tenant, username);
        auditService.authLoginFailure(tenant, username, reason);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
