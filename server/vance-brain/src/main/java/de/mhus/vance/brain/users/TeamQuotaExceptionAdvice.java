package de.mhus.vance.brain.users;

import de.mhus.vance.shared.team.TeamQuotaService.TeamQuotaExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link TeamQuotaExceededException} into HTTP 409 for every
 * endpoint that makes a team counted — create, and enabling a disabled team.
 *
 * <p>409 because the request is fine and conflicts only with the tenant's
 * current state — the same call succeeds once a team is disabled or deleted,
 * or the limit is raised.
 */
@RestControllerAdvice
@Slf4j
public class TeamQuotaExceptionAdvice {

    @ExceptionHandler(TeamQuotaExceededException.class)
    public ResponseEntity<Body> onQuotaExceeded(TeamQuotaExceededException ex) {
        log.trace("team quota exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Body("team_quota_exceeded", ex.getMessage(), ex.active(), ex.limit()));
    }

    public record Body(String error, String message, long active, int limit) {}
}
