package de.mhus.vance.brain.jaglan;

import java.util.LinkedHashMap;
import java.util.Map;

import de.mhus.vance.shared.document.jaglan.JaglanAccessException;
import de.mhus.vance.shared.document.jaglan.JaglanUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the two Jaglan refusals into the answers their own contract promises,
 * for every REST endpoint that can reach a mounted path.
 *
 * <p>Without this both are plain {@code RuntimeException}s and arrive at the
 * client as a 500 with a generic body — a user pressing "delete" on a mounted
 * document got "internal server error" instead of the named reason
 * {@code moveToTrash} had carefully produced. The split matters as much as the
 * status: a refusal is a stable property of the source and the caller should
 * stop asking, an outage is worth retrying.
 *
 * <p>An advice rather than two more {@code @ExceptionHandler}s on
 * {@code DocumentController}: mounted paths are reachable from more than one
 * controller (WebDAV, the addon surfaces), and a controller-local handler
 * would answer for one of them only. A controller that wants a different
 * answer still wins — its own handler is preferred over an advice.
 */
@RestControllerAdvice
@Slf4j
public class JaglanExceptionAdvice {

    /**
     * The source, or we, refused: read-only mount, an operation the protocol
     * does not implement, a path that escapes the mount, or a mount nobody
     * configured. 409, the same shape {@code document_locked} uses.
     */
    @ExceptionHandler(JaglanAccessException.class)
    public ResponseEntity<Map<String, Object>> onRefused(JaglanAccessException ex) {
        log.debug("mount refused: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("mount_refused",
                ex.getMount(), ex.getMessage()));
    }

    /**
     * The mount could not be reached — or there is no Jaglan implementation in
     * this process at all. 503 rather than 404: the document is not gone, and
     * telling a reader it does not exist is the one answer this subsystem
     * spends most of its design avoiding.
     */
    @ExceptionHandler(JaglanUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onUnavailable(JaglanUnavailableException ex) {
        log.debug("mount unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body("mount_unavailable",
                ex.getMount(), ex.getMessage()));
    }

    private static Map<String, Object> body(
            String error, @Nullable String mount, @Nullable String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        if (mount != null) body.put("mount", mount);
        body.put("message", message == null ? error : message);
        return body;
    }
}
