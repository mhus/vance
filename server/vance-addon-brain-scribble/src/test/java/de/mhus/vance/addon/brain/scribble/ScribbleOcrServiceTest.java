package de.mhus.vance.addon.brain.scribble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScribbleOcrServiceTest {

    private static final String YAML = "application/yaml";

    private DocumentService documentService;
    private LightLlmService lightLlmService;
    private ScribbleOcrService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        lightLlmService = mock(LightLlmService.class);
        service = new ScribbleOcrService(
                new ScribbleService(documentService, mock(SecurityContextFactory.class)),
                lightLlmService,
                documentService,
                mock(SecurityContextFactory.class));
    }

    private DocumentDocument sheetDoc(ScribbleSheet sheet) {
        String body = ScribbleCodec.serialize(sheet, YAML);
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("id1");
        when(doc.getTenantId()).thenReturn("t");
        when(doc.getMimeType()).thenReturn(YAML);
        when(doc.getPath()).thenReturn("notes.scribble.yaml");
        when(doc.getTitle()).thenReturn("Notes");
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(documentService.createOrReplaceBinary(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);
        return doc;
    }

    private static ScribbleSheet sheetWithInk() {
        ScribbleStroke stroke = new ScribbleStroke(
                ScribbleStroke.Tool.PEN,
                "1",
                ScribbleStroke.Width.M,
                List.of(new ScribblePoint(10, 10, 0.5), new ScribblePoint(40, 40, 0.7)));
        return new ScribbleSheet("Notes", ScribbleSize.a4Portrait(), List.of(stroke));
    }

    @Test
    void transcribe_storesMarkdownNextToSheet_withMarker() {
        when(lightLlmService.call(any())).thenReturn("Einkaufsliste\n- Milch");
        ScribbleOcrService.OcrResult result = service.transcribe("t", "p", sheetDoc(sheetWithInk()), "alice");

        assertThat(result.mdPath()).isEqualTo("notes.scribble.md");
        assertThat(result.markdown()).contains("Einkaufsliste").contains("scribble-ocr of notes.scribble.yaml");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LightLlmRequest> req = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlmService).call(req.capture());
        assertThat(req.getValue().getImageData()).isNotNull().isNotEmpty();
        assertThat(req.getValue().getImageMime()).isEqualTo("image/png");
        assertThat(req.getValue().getRecipeName()).isEqualTo(ScribbleOcrService.RECIPE_NAME);

        // stored at the derived path, markdown mime
        org.mockito.Mockito.verify(documentService)
                .createOrReplaceBinary(
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq("notes.scribble.md"),
                        any(),
                        org.mockito.ArgumentMatchers.eq("text/markdown"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void blankSheetIsRejected() {
        DocumentDocument doc = sheetDoc(new ScribbleSheet("Empty", ScribbleSize.a4Portrait(), List.of()));
        assertThatThrownBy(() -> service.transcribe("t", "p", doc, "alice"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no strokes");
    }

    @Test
    void visionFailurePropagatesAsToolException() {
        when(lightLlmService.call(any()))
                .thenThrow(new de.mhus.vance.brain.ai.light.LightLlmException("Model 'x' has no VISION capability"));
        assertThatThrownBy(() -> service.transcribe("t", "p", sheetDoc(sheetWithInk()), "alice"))
                .isInstanceOf(de.mhus.vance.brain.ai.light.LightLlmException.class)
                .hasMessageContaining("VISION");
    }
}
