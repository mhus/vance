package de.mhus.vance.addon.brain.scribble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.scribble.ScribbleCodec;
import de.mhus.vance.addon.brain.scribble.ScribbleService;
import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delivery-path tests for {@code scribble_sheet_image}. The vision
 * pre-check's negative branch is deliberately not unit-tested here: it
 * runs through the static {@code ChatBehaviorBuilder.fromProcess} chain
 * that belongs to the engine — this suite pins the fail-open contract
 * (no process / unknown process → deliver) instead, and the composer in
 * the brain remains the authoritative gate.
 */
class ScribbleSheetImageToolTest {

    private static final String YAML = "application/yaml";

    private EddieContext eddieContext;
    private DocumentService documentService;
    private ScribbleService scribbleService;
    private ThinkProcessService thinkProcessService;
    private ScribbleSheetImageTool tool;

    @BeforeEach
    void setUp() {
        eddieContext = mock(EddieContext.class);
        documentService = mock(DocumentService.class);
        scribbleService = new ScribbleService(documentService, mock(SecurityContextFactory.class));
        thinkProcessService = mock(ThinkProcessService.class);
        tool = new ScribbleSheetImageTool(
                eddieContext,
                documentService,
                scribbleService,
                thinkProcessService,
                mock(SettingService.class),
                mock(AiModelResolver.class),
                mock(ModelCatalog.class));
    }

    private ToolInvocationContext ctx(String processId) {
        return new ToolInvocationContext("t", "p", null, processId, "u", null);
    }

    private void sheetDoc(ScribbleSheet sheet) {
        String body = ScribbleCodec.serialize(sheet, YAML);
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("id1");
        when(doc.getTenantId()).thenReturn("t");
        when(doc.getMimeType()).thenReturn(YAML);
        when(doc.getPath()).thenReturn("notes.scribble.yaml");
        when(doc.getTitle()).thenReturn("T");
        when(doc.getKind()).thenReturn(ScribbleService.KIND);
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(doc));
        ProjectDocument project = mock(ProjectDocument.class);
        when(project.getName()).thenReturn("p");
        when(eddieContext.resolveProject(any(), any(), anyBoolean())).thenReturn(project);
    }

    private static ScribbleSheet sheetWithInk() {
        ScribbleStroke stroke = new ScribbleStroke(
                ScribbleStroke.Tool.PEN,
                "1",
                ScribbleStroke.Width.M,
                List.of(new ScribblePoint(50, 50, 0.5), new ScribblePoint(200, 120, 0.8)));
        return new ScribbleSheet("T", ScribbleSize.a4Portrait(), List.of(stroke));
    }

    @Test
    void deliversImageBlockWithoutProcess() {
        sheetDoc(sheetWithInk());
        Map<String, Object> result = tool.invoke(Map.of("path", "notes.scribble.yaml"), ctx(null));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("width")).isEqualTo(1240);
        assertThat(result.get("height")).isEqualTo(1754);
        assertThat(result.get("strokeCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("image");
        assertThat(content.get(0).get("mimeType")).isEqualTo("image/png");
        byte[] png = Base64.getDecoder().decode((String) content.get(0).get("data"));
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
    }

    @Test
    void unknownProcessFailsOpenToDelivery() {
        sheetDoc(sheetWithInk());
        when(thinkProcessService.findById(any())).thenReturn(Optional.empty());
        Map<String, Object> result = tool.invoke(Map.of("path", "notes.scribble.yaml"), ctx("proc-404"));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("content")).isNotNull();
    }

    @Test
    void blankSheetAnswersWithNoteAndNoImage() {
        sheetDoc(new ScribbleSheet("T", ScribbleSize.a4Portrait(), List.of()));
        Map<String, Object> result = tool.invoke(Map.of("path", "notes.scribble.yaml"), ctx(null));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat((String) result.get("note")).contains("no strokes");
        assertThat(result.get("content")).isNull();
    }

    @Test
    void dpiIsCappedAtMax() {
        sheetDoc(sheetWithInk());
        Map<String, Object> result = tool.invoke(Map.of("path", "notes.scribble.yaml", "dpi", 5000), ctx(null));
        assertThat(result.get("dpi")).isEqualTo(ScribbleSheetImageTool.DPI_MAX);
        assertThat((String) result.get("note")).contains("capped");
    }

    @Test
    void regionIsRenderedAndReported() {
        sheetDoc(sheetWithInk());
        Map<String, Object> region = Map.of("x", 100, "y", 100, "w", 400, "h", 300);
        Map<String, Object> result = tool.invoke(Map.of("path", "notes.scribble.yaml", "region", region), ctx(null));
        assertThat(result.get("width")).isEqualTo(400);
        assertThat(result.get("height")).isEqualTo(300);
    }

    @Test
    void regionOutsideTheSheetThrowsFriendly() {
        sheetDoc(sheetWithInk());
        Map<String, Object> region = Map.of("x", -900, "y", -900, "w", 10, "h", 10);
        assertThatThrownBy(() -> tool.invoke(Map.of("path", "notes.scribble.yaml", "region", region), ctx(null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Region does not intersect");
    }

    @Test
    void nonScribbleDocumentIsRejected() {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getPath()).thenReturn("notes.md");
        when(doc.getKind()).thenReturn("workpage");
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(doc));
        ProjectDocument project = mock(ProjectDocument.class);
        when(project.getName()).thenReturn("p");
        when(eddieContext.resolveProject(any(), any(), anyBoolean())).thenReturn(project);
        assertThatThrownBy(() -> tool.invoke(Map.of("path", "notes.md"), ctx(null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not a scribble");
    }
}
