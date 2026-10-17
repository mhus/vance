package de.mhus.vance.addon.brain.contacts.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.contacts.ContactConflictException;
import de.mhus.vance.addon.brain.contacts.ContactNotFoundException;
import de.mhus.vance.addon.brain.contacts.GravatarUnavailableException;
import de.mhus.vance.shared.document.kind.KindCodecException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ContactsErrorAdviceTest {

    private final ContactsErrorAdvice advice = new ContactsErrorAdvice();

    private static void assertError(
            ResponseEntity<Map<String, Object>> r, HttpStatus status, String reason, String message) {
        assertThat(r.getStatusCode()).isEqualTo(status);
        assertThat(r.getBody()).containsEntry("reason", reason).containsEntry("message", message);
    }

    @Test
    void notFoundIs404() {
        assertError(
                advice.notFound(new ContactNotFoundException("No contact 'x'")),
                HttpStatus.NOT_FOUND,
                "contact-not-found",
                "No contact 'x'");
    }

    @Test
    void invalidInputIs400() {
        assertError(
                advice.badRequest(new IllegalArgumentException("'card' is required")),
                HttpStatus.BAD_REQUEST,
                "contact-invalid",
                "'card' is required");
    }

    @Test
    void concurrentChangeIs409() {
        assertError(
                advice.conflict(new ContactConflictException("Changed meanwhile", null)),
                HttpStatus.CONFLICT,
                "contact-conflict",
                "Changed meanwhile");
    }

    @Test
    void unreadableCardIs422() {
        assertError(
                advice.unreadable(new KindCodecException("bad yaml")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "contact-unreadable",
                "bad yaml");
    }

    @Test
    void gravatarDownIs502() {
        assertError(
                advice.gravatarDown(new GravatarUnavailableException("timeout", null)),
                HttpStatus.BAD_GATEWAY,
                "gravatar-unavailable",
                "timeout");
    }

    @Test
    void missingMessageFallsBackToTheStatusPhrase() {
        assertError(
                advice.badRequest(new IllegalArgumentException()),
                HttpStatus.BAD_REQUEST,
                "contact-invalid",
                HttpStatus.BAD_REQUEST.getReasonPhrase());
    }
}
