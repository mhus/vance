package de.mhus.vance.brain.users;

import de.mhus.vance.shared.user.UserQuotaService.UserQuotaExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link UserQuotaExceededException} into HTTP 409 for every
 * endpoint that makes an account counted — create, and a status change out of
 * DISABLED.
 *
 * <p>409 because the request is fine and conflicts only with the tenant's
 * current state — the same call succeeds once an account is disabled or
 * deleted, or the limit is raised. {@code quota} says which of the two limits
 * was hit ({@code seats} / {@code serviceAccounts}).
 */
@RestControllerAdvice
@Slf4j
public class UserQuotaExceptionAdvice {

    @ExceptionHandler(UserQuotaExceededException.class)
    public ResponseEntity<Body> onQuotaExceeded(UserQuotaExceededException ex) {
        log.trace("user quota exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Body("user_quota_exceeded", ex.kind().wireName(), ex.getMessage(), ex.active(), ex.limit()));
    }

    public record Body(String error, String quota, String message, long active, int limit) {}
}
