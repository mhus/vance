package de.mhus.vance.brain.projects;

import de.mhus.vance.shared.project.ProjectQuotaService.ProjectQuotaExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link ProjectQuotaExceededException} into HTTP 409 for every
 * endpoint that makes a project active — create, copy, unarchive.
 *
 * <p>An advice rather than a catch per endpoint: the quota is checked inside
 * {@code ProjectLifecycleService}, reachable from several controllers, and
 * without this the refusal arrives as a generic 500. 409 because the request
 * is fine and conflicts only with the tenant's current state — the same call
 * succeeds once a project is archived or the limit is raised.
 */
@RestControllerAdvice
@Slf4j
public class ProjectQuotaExceptionAdvice {

    @ExceptionHandler(ProjectQuotaExceededException.class)
    public ResponseEntity<Body> onQuotaExceeded(ProjectQuotaExceededException ex) {
        log.trace("project quota exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Body("project_quota_exceeded", ex.getMessage(), ex.active(), ex.limit()));
    }

    public record Body(String error, String message, long active, int limit) {}
}
