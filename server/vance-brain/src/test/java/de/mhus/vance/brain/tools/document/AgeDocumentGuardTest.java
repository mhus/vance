package de.mhus.vance.brain.tools.document;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.documents.AgeDocumentKind;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/**
 * The named age refusals every doc tool funnels through — readable,
 * writable, creatable. The guard decisions themselves (which tools call
 * which guard) are one line per tool and covered by review; this class
 * pins the marker logic and the messages an LLM actually sees.
 */
class AgeDocumentGuardTest {

    private static final String ARMOR = """
            -----BEGIN AGE ENCRYPTED FILE-----
            YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOSB0QXVkQmNwZ3Z6YnNRZDJP
            -----END AGE ENCRYPTED FILE-----
            """;

    private static DocumentDocument doc(String kind, String mime) {
        DocumentDocument d = new DocumentDocument();
        d.setTenantId("t1");
        d.setProjectId("p1");
        d.setPath("documents/secret.md.age");
        d.setKind(kind);
        d.setMimeType(mime);
        return d;
    }

    // ───────────────────────── requireReadable ─────────────────────────

    @Test
    void requireReadable_ageKind_refuses() {
        assertThatThrownBy(() -> AgeDocumentGuard.requireReadable(doc("age", null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("age-encrypted")
                .hasMessageContaining("Ask the user");
    }

    @Test
    void requireReadable_ageMime_refuses() {
        // Uploaded .age without a stamped kind — the mime alone must be
        // enough for every guard.
        assertThatThrownBy(() -> AgeDocumentGuard.requireReadable(
                doc(null, AgeDocumentKind.MIME_TYPE)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("age-encrypted");
    }

    @Test
    void requireReadable_plainDoc_passes() {
        assertThatCode(() -> AgeDocumentGuard.requireReadable(
                doc("workpage", "text/markdown")))
                .doesNotThrowAnyException();
    }

    // ───────────────────────── requireWritable ─────────────────────────

    @Test
    void requireWritable_ageDoc_refuses() {
        assertThatThrownBy(() -> AgeDocumentGuard.requireWritable(
                doc("age", AgeDocumentKind.MIME_TYPE)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("destroy the document");
    }

    @Test
    void requireWritable_plainDoc_passes() {
        assertThatCode(() -> AgeDocumentGuard.requireWritable(
                doc("text", "text/plain")))
                .doesNotThrowAnyException();
    }

    // ───────────────────────── requireCreatable ─────────────────────────

    @Test
    void requireCreatable_kindAgeWithPlainBody_refuses() {
        assertThatThrownBy(() -> AgeDocumentGuard.requireCreatable("age", "# generated"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("cannot produce");
    }

    @Test
    void requireCreatable_kindAgeWithArmoredBody_passes() {
        // Moving real ciphertext (e.g. from a workspace file) is exactly
        // what age documents are for — the guard must not block it.
        assertThatCode(() -> AgeDocumentGuard.requireCreatable("age", ARMOR))
                .doesNotThrowAnyException();
    }

    @Test
    void requireCreatable_otherKind_passes() {
        assertThatCode(() -> AgeDocumentGuard.requireCreatable("text", "# generated"))
                .doesNotThrowAnyException();
    }
}
