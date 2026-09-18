package de.mhus.vance.addon.brain.scribble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.scribble.ScribbleCodec;
import de.mhus.vance.addon.brain.scribble.ScribbleService;
import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScribbleDrawToolsTest {

    private static final String YAML = "application/yaml";

    private EddieContext eddieContext;
    private DocumentService documentService;
    private ScribbleService scribbleService;
    private ScribbleDrawTools.ShapeAddTool shapeTool;
    private ScribbleDrawTools.StrokeAddTool strokeTool;
    private ScribbleDrawTools.StrokeDeleteTool deleteTool;

    @BeforeEach
    void setUp() {
        eddieContext = mock(EddieContext.class);
        documentService = mock(DocumentService.class);
        scribbleService = new ScribbleService(documentService, mock(SecurityContextFactory.class));
        shapeTool = new ScribbleDrawTools.ShapeAddTool(eddieContext, documentService, scribbleService);
        strokeTool = new ScribbleDrawTools.StrokeAddTool(eddieContext, documentService, scribbleService);
        deleteTool = new ScribbleDrawTools.StrokeDeleteTool(eddieContext, documentService, scribbleService);
    }

    private ToolInvocationContext ctx() {
        return new ToolInvocationContext("t", "p", null, null, "u", null);
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
        when(documentService.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(doc));
        ProjectDocument project = mock(ProjectDocument.class);
        when(project.getName()).thenReturn("p");
        when(eddieContext.resolveProject(any(), any(), anyBoolean())).thenReturn(project);
    }

    private static ScribbleSheet blankSheet() {
        return ScribbleSheet.empty("T");
    }

    private ScribbleSheet savedSheet() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(documentService)
                .update(any(), any(), any(), cap.capture(), any(), any(), any(), any(), any(), any(), any());
        return ScribbleCodec.parse(cap.getValue(), YAML);
    }

    // ── Geometry ─────────────────────────────────────────────────

    @Test
    void rectangle_outlineIsClosedPolyline() {
        List<List<ScribblePoint>> outlines = ScribbleShapeGeometry.outline("rectangle", 100, 200, 400.0, 500.0);
        assertThat(outlines).hasSize(1);
        List<ScribblePoint> pts = outlines.get(0);
        assertThat(pts).hasSize(5);
        assertThat(pts.get(0)).isEqualTo(new ScribblePoint(100, 200, 0.5));
        assertThat(pts.get(4)).isEqualTo(pts.get(0)); // closed
    }

    @Test
    void circle_outlineIsSampledAndClosed() {
        List<List<ScribblePoint>> outlines = ScribbleShapeGeometry.outline("circle", 620, 877, 300.0, null);
        List<ScribblePoint> pts = outlines.get(0);
        assertThat(pts).hasSize(49);
        assertThat(pts.get(48)).isEqualTo(pts.get(0));
        // extreme points sit on the circle
        assertThat(pts.get(0).x()).isEqualTo(920);
        // index 36 = 270° = topmost point (y grows down)
        assertThat(pts.get(36).y()).isEqualTo(577);
    }

    @Test
    void arrow_isShaftPlusTwoHeadLines() {
        List<List<ScribblePoint>> outlines = ScribbleShapeGeometry.outline("arrow", 0, 0, 400.0, 0.0);
        assertThat(outlines).hasSize(3);
        // all head lines end at the tip
        assertThat(outlines.get(1).get(0)).isEqualTo(new ScribblePoint(400, 0, 0.5));
        assertThat(outlines.get(2).get(0)).isEqualTo(new ScribblePoint(400, 0, 0.5));
    }

    @Test
    void unknownShape_throws() {
        assertThatThrownBy(() -> ScribbleShapeGeometry.outline("house", 0, 0, 10.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown shape");
    }

    @Test
    void boundsCheck_rejectsOffPagePoints() {
        ScribbleSize a4 = ScribbleSize.a4Portrait();
        List<ScribblePoint> off = List.of(new ScribblePoint(1300, 100, 0.5));
        assertThatThrownBy(() -> ScribbleShapeGeometry.assertInBounds(off, a4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the sheet raster");
    }

    // ── Tools ────────────────────────────────────────────────────

    @Test
    void shapeAdd_appendsStrokesAndReportsIndex() {
        sheetDoc(blankSheet());
        Map<String, Object> result = shapeTool.invoke(
                Map.of("path", "notes.scribble.yaml", "shape", "rectangle", "x", 100, "y", 100, "x2", 500, "y2", 400),
                ctx());
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("strokesAdded")).isEqualTo(1);
        assertThat(result.get("firstStrokeIndex")).isEqualTo(0);
        assertThat(savedSheet().strokes()).hasSize(1);
    }

    @Test
    void shapeAdd_offPageFailsWithoutWriting() {
        sheetDoc(blankSheet());
        assertThatThrownBy(() -> shapeTool.invoke(
                        Map.of("path", "notes.scribble.yaml", "shape", "circle", "x", 100, "y", 100, "x2", 2000),
                        ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("outside the sheet raster");
    }

    @Test
    void strokeAdd_appendsOnePolyline() {
        sheetDoc(blankSheet());
        Map<String, Object> result = strokeTool.invoke(
                Map.of("path", "notes.scribble.yaml", "points", List.of(List.of(10, 20), List.of(30, 40))), ctx());
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("strokeIndex")).isEqualTo(0);
        ScribbleSheet saved = savedSheet();
        assertThat(saved.strokes()).hasSize(1);
        assertThat(saved.strokes().get(0).points().get(1).x()).isEqualTo(30);
    }

    @Test
    void strokeAdd_rejectsShortPointList() {
        sheetDoc(blankSheet());
        assertThatThrownBy(() -> strokeTool.invoke(
                        Map.of("path", "notes.scribble.yaml", "points", List.of(List.of(10, 20))), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void strokeDelete_removesIndexedStroke() {
        sheetDoc(blankSheet()
                .withStrokes(List.of(
                        new de.mhus.vance.addon.brain.scribble.model.ScribbleStroke(
                                de.mhus.vance.addon.brain.scribble.model.ScribbleStroke.Tool.PEN,
                                "1",
                                de.mhus.vance.addon.brain.scribble.model.ScribbleStroke.Width.M,
                                List.of(new ScribblePoint(1, 1, 0.5), new ScribblePoint(2, 2, 0.5))),
                        new de.mhus.vance.addon.brain.scribble.model.ScribbleStroke(
                                de.mhus.vance.addon.brain.scribble.model.ScribbleStroke.Tool.PEN,
                                "2",
                                de.mhus.vance.addon.brain.scribble.model.ScribbleStroke.Width.M,
                                List.of(new ScribblePoint(3, 3, 0.5), new ScribblePoint(4, 4, 0.5))))));
        Map<String, Object> result = deleteTool.invoke(Map.of("path", "notes.scribble.yaml", "index", 0), ctx());
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("remainingStrokes")).isEqualTo(1);
        // the remaining stroke is the red one (color "2")
        assertThat(savedSheet().strokes().get(0).color()).isEqualTo("2");
    }

    @Test
    void strokeDelete_rejectsOutOfRange() {
        sheetDoc(blankSheet());
        assertThatThrownBy(() -> deleteTool.invoke(Map.of("path", "notes.scribble.yaml", "index", 5), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("out of range");
    }
}
