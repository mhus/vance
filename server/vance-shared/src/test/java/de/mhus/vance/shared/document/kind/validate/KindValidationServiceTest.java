package de.mhus.vance.shared.document.kind.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentHeaderParser;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.KindRegistry;
import de.mhus.vance.shared.document.kind.KindHandler;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the unknown-tolerance ladder in {@link KindValidationService}
 * (planning/kind-handler.md §4). Framework glue (Spring wiring, header
 * parsing) is mocked — only the dispatch + tolerance logic is under test.
 */
class KindValidationServiceTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final KindRegistry kindRegistry = mock(KindRegistry.class);
    private final DocumentHeaderParser headerParser = mock(DocumentHeaderParser.class);

    private final KindValidationService service =
            new KindValidationService(documentService, kindRegistry, headerParser);

    @Test
    void validateContent_noKind_isOkWithNoFindings() {
        // No explicit kind and no header → nothing to validate.
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());

        KindValidationResult result =
                service.validateContent("t1", "p1", null, "just some plain text", null);

        assertThat(result.ok()).isTrue();
        assertThat(result.findings()).isEmpty();
        assertThat(result.errors()).isZero();
    }

    @Test
    void validateContent_unknownKind_isOkWithSingleWarning() {
        when(kindRegistry.handlerFor("frobnicate")).thenReturn(null);

        KindValidationResult result =
                service.validateContent("t1", "p1", "frobnicate", "content", null);

        assertThat(result.ok()).isTrue();
        assertThat(result.findings()).hasSize(1);
        Finding f = result.findings().get(0);
        assertThat(f.level()).isEqualTo(Finding.Level.WARNING);
        assertThat(f.code()).isEqualTo("kind-unknown");
        assertThat(result.warnings()).isEqualTo(1);
    }

    @Test
    void validateContent_knownKind_delegatesAndSurfacesFindings() {
        Finding handlerError = Finding.error("here", "bad-shape", "not a valid records doc");
        KindHandler handler = mock(KindHandler.class);
        when(handler.validate(any(), any())).thenReturn(List.of(handlerError));
        when(kindRegistry.handlerFor("records")).thenReturn(handler);

        KindValidationResult result =
                service.validateContent("t1", "p1", "records", "{}", null);

        assertThat(result.findings()).containsExactly(handlerError);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).isEqualTo(1);
    }
}
