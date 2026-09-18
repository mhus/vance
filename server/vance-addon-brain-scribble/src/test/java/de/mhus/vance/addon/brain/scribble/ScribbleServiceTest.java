package de.mhus.vance.addon.brain.scribble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScribbleServiceTest {

    private static final String YAML = "application/yaml";
    private static final String USER = "alice";

    private DocumentService documentService;
    private SecurityContextFactory contextFactory;
    private ScribbleService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        contextFactory = mock(SecurityContextFactory.class);
        service = new ScribbleService(documentService, contextFactory);
    }

    private DocumentDocument docWithBody(String body) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("id1");
        when(doc.getTenantId()).thenReturn("t");
        when(doc.getMimeType()).thenReturn(YAML);
        when(doc.getPath()).thenReturn("notes.scribble.yaml");
        when(doc.getTitle()).thenReturn("T");
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(documentService.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);
        return doc;
    }

    private String capturedBody() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(documentService)
                .update(eq("id1"), any(), any(), cap.capture(), any(), any(), any(), any(), any(), any(), any());
        return cap.getValue();
    }

    @Test
    void create_normalisesPathAndStoresEmptySheet() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        DocumentDocument stored = mock(DocumentDocument.class);
        when(stored.getPath()).thenReturn("notes.scribble.yaml");
        ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        when(documentService.create(any(), any(), any(), any(), any(), any(), body.capture(), any(), any()))
                .thenReturn(stored);

        service.create("t", "p", "notes", "Notizen", USER);

        // Path got the .scribble.yaml extension, the body is a valid empty sheet.
        verify(documentService)
                .create(
                        eq("t"),
                        eq("p"),
                        eq("notes.scribble.yaml"),
                        eq("Notizen"),
                        any(),
                        eq(YAML),
                        any(),
                        eq(USER),
                        any());
        ScribbleSheet sheet = ScribbleCodec.parse(new String(readAll(body.getValue()), StandardCharsets.UTF_8), YAML);
        assertThat(sheet.title()).isEqualTo("Notizen");
        assertThat(sheet.strokes()).isEmpty();
    }

    @Test
    void create_existingPath_throws() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(mock(DocumentDocument.class)));

        assertThatThrownBy(() -> service.create("t", "p", "notes.scribble.yaml", null, USER))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void writeSheet_serializesStrokesAndKeepsDocumentTitleWhenNull() {
        DocumentDocument doc = docWithBody(ScribbleCodec.serialize(ScribbleSheet.empty("T"), YAML));

        ScribbleStroke stroke = new ScribbleStroke(
                ScribbleStroke.Tool.PEN,
                "3",
                ScribbleStroke.Width.L,
                List.of(new ScribblePoint(5, 6, 0.7), new ScribblePoint(7, 8, 0.7)));
        service.writeSheet(doc, ScribbleSheet.empty(null).withStrokes(List.of(stroke)), USER);

        ScribbleSheet written = ScribbleCodec.parse(capturedBody(), YAML);
        assertThat(written.strokes()).hasSize(1);
        assertThat(written.strokes().get(0).color()).isEqualTo("3");
        assertThat(written.strokes().get(0).points()).hasSize(2);
        // null title keeps the document title, does not wipe it
        verify(documentService)
                .update(eq("id1"), eq("T"), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void writeSheet_threadsActingUserIntoWriteActor_notNullSystem() {
        // Security regression (canvas HIGH, code-review-2): a user-initiated
        // save must thread the real acting user into the write actor — a
        // hardcoded null maps to SecurityContext.SYSTEM and fail-opens the
        // per-document authz.
        DocumentDocument doc = docWithBody(ScribbleCodec.serialize(ScribbleSheet.empty("T"), YAML));

        service.writeSheet(doc, ScribbleSheet.empty("T"), USER);

        verify(contextFactory).writeActor(any(), eq(USER), any());
    }

    private static byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
