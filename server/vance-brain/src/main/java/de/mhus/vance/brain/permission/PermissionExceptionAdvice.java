package de.mhus.vance.brain.permission;

import de.mhus.vance.shared.permission.PermissionDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link PermissionDeniedException} into HTTP 403 for all REST
 * endpoints. WebSocket frames are handled separately inside
 * {@code VanceWebSocketHandler}; this advice only covers servlet requests.
 */
@RestControllerAdvice
@Slf4j
public class PermissionExceptionAdvice {

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Body> onDenied(PermissionDeniedException ex) {
        log.debug("permission denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new Body("permission_denied",
                        ex.getAction().name(),
                        ex.getResource().getClass().getSimpleName()));
    }

    public record Body(String error, String action, String resourceType) {}
}
