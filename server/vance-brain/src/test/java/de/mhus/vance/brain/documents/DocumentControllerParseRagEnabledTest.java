package de.mhus.vance.brain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit test for the tri-state {@code ragEnabled} wire-format parser
 * shared between the create and update controllers. The parser is the
 * single source of truth for the allowed vocabulary
 * ({@code auto}/{@code on}/{@code off}); a drift there flips every
 * caller's interpretation of the RAG-inclusion flag at once, so the
 * contract is worth a focused test.
 */
class DocumentControllerParseRagEnabledTest {

    @Test
    void parseRagEnabled_nullInput_returnsNull() {
        assertThat(DocumentController.parseRagEnabledTriState(null)).isNull();
    }

    @Test
    void parseRagEnabled_blankInput_returnsNull() {
        assertThat(DocumentController.parseRagEnabledTriState("")).isNull();
        assertThat(DocumentController.parseRagEnabledTriState("   ")).isNull();
    }

    @Test
    void parseRagEnabled_autoLiteral_clearsOverride() {
        // "auto" is the explicit way to say "fall back to default
        // eligibility" — distinct from absent input only in the update
        // controller, which uses absent-vs-auto to decide whether to
        // touch the field at all.
        assertThat(DocumentController.parseRagEnabledTriState("auto")).isNull();
        assertThat(DocumentController.parseRagEnabledTriState("AUTO")).isNull();
        assertThat(DocumentController.parseRagEnabledTriState("  auto  ")).isNull();
    }

    @Test
    void parseRagEnabled_onAndTrue_returnTrue() {
        assertThat(DocumentController.parseRagEnabledTriState("on")).isTrue();
        assertThat(DocumentController.parseRagEnabledTriState("ON")).isTrue();
        assertThat(DocumentController.parseRagEnabledTriState("true")).isTrue();
        assertThat(DocumentController.parseRagEnabledTriState("True")).isTrue();
    }

    @Test
    void parseRagEnabled_offAndFalse_returnFalse() {
        // The chat-export flow depends on this branch: a client sending
        // "off" must produce a persisted ragEnabled=false so the indexer
        // never enqueues the auto-generated document.
        assertThat(DocumentController.parseRagEnabledTriState("off")).isFalse();
        assertThat(DocumentController.parseRagEnabledTriState("OFF")).isFalse();
        assertThat(DocumentController.parseRagEnabledTriState("false")).isFalse();
        assertThat(DocumentController.parseRagEnabledTriState("False")).isFalse();
    }

    @Test
    void parseRagEnabled_unknownToken_throws400() {
        assertThatThrownBy(() -> DocumentController.parseRagEnabledTriState("maybe"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
