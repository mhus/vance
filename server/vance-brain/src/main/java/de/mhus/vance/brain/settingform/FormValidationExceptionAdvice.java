package de.mhus.vance.brain.settingform;

import de.mhus.vance.shared.form.FormValidationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link FormValidationException} into a structured HTTP 400 for
 * all REST endpoints (Setting Forms apply/validate, Wizard render). The body
 * carries the per-field {@code fieldErrors} list so the Web-UI can render
 * errors inline instead of receiving an opaque {@code "Bad Request"} — the
 * default Spring error body drops the exception message unless
 * {@code server.error.include-message} is set, and even then only surfaces a
 * flat string.
 */
@RestControllerAdvice
@Slf4j
public class FormValidationExceptionAdvice {

    @ExceptionHandler(FormValidationException.class)
    public ResponseEntity<Body> onValidationFailed(FormValidationException ex) {
        log.debug("form validation failed: {}", ex.getMessage());
        List<FieldError> fieldErrors = ex.getErrors().stream()
                .map(e -> new FieldError(e.field(), e.error()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Body("form_validation_failed", ex.getMessage(), fieldErrors));
    }

    /** One offending field: {@code field}-path plus short error code. */
    public record FieldError(String field, String error) {}

    public record Body(String error, String message, List<FieldError> fieldErrors) {}
}
