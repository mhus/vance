package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The document is what makes an adam Trillian persistent, and it is also
 * a file a human may open and edit. Both directions have to hold.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(
        strictness = org.mockito.quality.Strictness.LENIENT)
class TrillianAttributeStoreTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test1";
    private static final String ACCOUNT = "_trillian-adam-4711";

    @Mock
    DocumentService documentService;

    @Test
    void savedAttributes_readBackIdentically() {
        TrillianAttributeStore store = roundTrippingStore();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("persona", "witziger Schwabe");
        attrs.put("language", "Deutsch");

        store.save(TENANT, PROJECT, ACCOUNT, attrs);

        // Round-trip through the real YAML, not through a stubbed string:
        // the write is only worth anything if the read agrees with it.
        assertThat(store.load(TENANT, PROJECT, ACCOUNT)).isEqualTo(attrs);
    }

    @Test
    void aMultiLineValue_staysReadableInTheFile() {
        TrillianAttributeStore store = new TrillianAttributeStore(documentService);

        store.save(TENANT, PROJECT, ACCOUNT, Map.of("persona", "line one\nline two"));

        // A persona is the typical attribute and it is usually a
        // paragraph. Dumped as a quoted one-liner with escapes it would
        // be unopenable in an editor, which defeats the point of using a
        // document at all.
        assertThat(writtenText()).contains("line one").contains("line two");
        assertThat(writtenText()).doesNotContain("\\n");
    }

    @Test
    void theFileExplainsItself() {
        TrillianAttributeStore store = new TrillianAttributeStore(documentService);

        store.save(TENANT, PROJECT, ACCOUNT, Map.of("language", "Deutsch"));

        // Someone finds this file in Cortex without context; it has to
        // say what it is and that editing it works.
        assertThat(writtenText()).startsWith("#").contains("Trillian attributes");
    }

    @Test
    void anAbsentDocument_isNoAttributes() {
        when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThat(new TrillianAttributeStore(documentService)
                .load(TENANT, PROJECT, ACCOUNT)).isEmpty();
    }

    @Test
    void aBrokenFile_doesNotStopTheTrillian() {
        // Hand-editing is invited, so a broken file is a question of
        // when. Starting with no attributes beats not starting.
        givenStoredText("persona: [unclosed");

        assertThat(new TrillianAttributeStore(documentService)
                .load(TENANT, PROJECT, ACCOUNT)).isEmpty();
    }

    @Test
    void aFileThatIsNotAMap_isIgnored() {
        givenStoredText("just a sentence someone typed");

        assertThat(new TrillianAttributeStore(documentService)
                .load(TENANT, PROJECT, ACCOUNT)).isEmpty();
    }

    @Test
    void aFailingWrite_doesNotEscape() {
        // engineParams already carry the authoritative value at this
        // point — losing the mirror must not fail the attribute set.
        when(documentService.upsertText(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        new TrillianAttributeStore(documentService)
                .save(TENANT, PROJECT, ACCOUNT, Map.of("a", "b"));
    }

    @Test
    void thePath_isKeyedByAccount() {
        // The account name is the one identifier that survives archive
        // and reactivate, which is exactly the span the file must cover.
        assertThat(TrillianAttributeStore.pathFor(ACCOUNT))
                .isEqualTo("_vance/trillian/_trillian-adam-4711.yaml");
    }

    // ──── helpers ───────────────────────────────────────────────────────

    /**
     * A store whose writes are visible to its own reads — the mock plays
     * the document collection, so the round-trip exercises the real YAML
     * on both sides.
     */
    private TrillianAttributeStore roundTrippingStore() {
        DocumentDocument doc = new DocumentDocument();
        when(documentService.upsertText(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(), any()))
                .thenAnswer(inv -> {
                    when(documentService.readContent(doc)).thenReturn(inv.getArgument(5));
                    return doc;
                });
        when(documentService.findByPath(TENANT, PROJECT,
                TrillianAttributeStore.pathFor(ACCOUNT))).thenReturn(Optional.of(doc));
        return new TrillianAttributeStore(documentService);
    }

    /** The text handed to upsertText, then fed back to findByPath/readContent. */
    private String writtenText() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(eq(TENANT), eq(PROJECT), anyString(), any(), any(),
                text.capture(), any(), any());
        return text.getValue();
    }

    private void givenStoredText(String text) {
        DocumentDocument doc = new DocumentDocument();
        when(documentService.findByPath(TENANT, PROJECT,
                TrillianAttributeStore.pathFor(ACCOUNT))).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(text);
    }
}
