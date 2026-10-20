package de.mhus.vance.brain.documents;

import de.mhus.vance.shared.storage.StorageQuotaExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link StorageQuotaExceededException} into HTTP 507 for every
 * REST endpoint that writes content — document create/update, uploads,
 * archives, the addon surfaces.
 *
 * <p>An advice rather than a controller-local handler: storage writes are
 * reachable from many controllers, and without this the refusal arrives as a
 * generic 500 instead of the named limit the exception carries. 507
 * (Insufficient Storage) rather than 413: the request itself is fine, the
 * tenant has run out of room — the same write succeeds after a cleanup.
 */
@RestControllerAdvice
@Slf4j
public class StorageQuotaExceptionAdvice {

    @ExceptionHandler(StorageQuotaExceededException.class)
    public ResponseEntity<Body> onQuotaExceeded(StorageQuotaExceededException ex) {
        log.trace("storage quota exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                .body(new Body("storage_quota_exceeded", ex.getMessage(), ex.currentUsageBytes(), ex.limitBytes()));
    }

    public record Body(String error, String message, long usedBytes, long limitBytes) {}
}
