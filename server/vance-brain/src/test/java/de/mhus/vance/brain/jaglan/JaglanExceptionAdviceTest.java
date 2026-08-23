package de.mhus.vance.brain.jaglan;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.jaglan.JaglanAccessException;
import de.mhus.vance.shared.document.jaglan.JaglanUnavailableException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Both Jaglan failures used to leave the server as a plain 500: neither
 * carries {@code @ResponseStatus} and no handler existed, so a user pressing
 * "delete" on a mounted document got "internal server error" instead of the
 * named reason the document layer had produced. The split between the two is
 * the point — stop asking versus try again.
 */
class JaglanExceptionAdviceTest {

    private JaglanExceptionAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new JaglanExceptionAdvice();
    }

    @Test
    void refusal_is409WithTheMountAndTheReason() {
        ResponseEntity<Map<String, Object>> response = advice.onRefused(
                new JaglanAccessException("library", "mount 'library' is read-only"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("error", "mount_refused")
                .containsEntry("mount", "library")
                .containsEntry("message", "mount 'library' is read-only");
    }

    @Test
    void outage_is503_soTheDocumentIsNotReportedAsGone() {
        ResponseEntity<Map<String, Object>> response = advice.onUnavailable(
                new JaglanUnavailableException("library", "connect timeout"));

        // Not 404: the file exists, and telling a reader otherwise is the one
        // answer this subsystem spends its design avoiding.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "mount_unavailable");
    }

    @Test
    void noJaglanInThisProcess_stillAnswersWithoutAMountName() {
        ResponseEntity<Map<String, Object>> response = advice.onUnavailable(
                new JaglanUnavailableException(null, "no mount support in this process"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).doesNotContainKey("mount");
    }
}
