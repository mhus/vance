package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.ContactNotFoundException;
import de.mhus.vance.addon.brain.contacts.GravatarUnavailableException;
import de.mhus.vance.shared.document.kind.KindCodecException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error mapping for the contacts REST surface only ({@code basePackageClasses}
 * scopes it to this package). Answers {@code {reason, message}} — the shape
 * the client's {@code RestError} reads. Permission refusals go through the
 * brain's global advice.
 */
@RestControllerAdvice(basePackageClasses = ContactsErrorAdvice.class)
@Slf4j
public class ContactsErrorAdvice {

    @ExceptionHandler(ContactNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ContactNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "contact-not-found", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "contact-invalid", e.getMessage());
    }

    @ExceptionHandler(KindCodecException.class)
    public ResponseEntity<Map<String, Object>> unreadable(KindCodecException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "contact-unreadable", e.getMessage());
    }

    @ExceptionHandler(GravatarUnavailableException.class)
    public ResponseEntity<Map<String, Object>> gravatarDown(GravatarUnavailableException e) {
        log.warn("Contacts: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "gravatar-unavailable", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String reason, @Nullable String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("message", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
