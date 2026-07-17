package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.addon.brain.canvas.model.CanvasEdge;
import de.mhus.vance.addon.brain.canvas.model.CanvasGraph;
import de.mhus.vance.addon.brain.canvas.model.CanvasNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps between the typed {@link CanvasDocument} model and the flat wire
 * DTOs. The {@code fromDto} direction routes through
 * {@link CanvasCodec#nodeFromMap} / {@link CanvasCodec#edgeFromMap} so
 * validation stays in one place.
 */
public final class CanvasDtoMapper {

    private CanvasDtoMapper() {}

    // ── model → dto ───────────────────────────────────────────────

    public static CanvasGraphDto toDto(CanvasDocument doc) {
        List<CanvasNodeDto> nodes = new ArrayList<>();
        for (CanvasNode n : doc.graph().nodes()) nodes.add(nodeToDto(n));
        List<CanvasEdgeDto> edges = new ArrayList<>();
        for (CanvasEdge e : doc.graph().edges()) edges.add(edgeToDto(e));
        return new CanvasGraphDto(doc.title(), doc.description(), nodes, edges);
    }

    private static CanvasNodeDto nodeToDto(CanvasNode n) {
        String text = null, ref = null, href = null, title = null, label = null, fontSize = null;
        String textColor = null, author = null;
        Boolean bold = null, italic = null;
        switch (n) {
            case CanvasNode.Text t -> {
                text = t.text(); bold = t.bold(); italic = t.italic(); fontSize = t.fontSize();
                textColor = t.textColor(); author = t.author();
            }
            case CanvasNode.Doc d -> ref = d.ref();
            case CanvasNode.Link l -> { href = l.href(); title = l.title(); }
            case CanvasNode.Group g -> label = g.label();
        }
        return new CanvasNodeDto(n.id(), n.type(), n.x(), n.y(), n.w(), n.h(),
                n.color(), n.z(), n.parent(), text, ref, href, title, label, bold, italic, fontSize,
                textColor, author);
    }

    private static CanvasEdgeDto edgeToDto(CanvasEdge e) {
        return new CanvasEdgeDto(
                e.id(), e.from(), e.to(),
                e.fromSide() != null ? e.fromSide().wire() : null,
                e.toSide() != null ? e.toSide().wire() : null,
                e.fromEnd().wire(), e.toEnd().wire(),
                e.label(), e.color(), e.dashed(), e.width());
    }

    // ── dto → model ───────────────────────────────────────────────

    public static CanvasDocument fromDto(CanvasGraphDto dto) {
        List<CanvasNode> nodes = new ArrayList<>();
        if (dto.nodes() != null) {
            for (CanvasNodeDto n : dto.nodes()) nodes.add(CanvasCodec.nodeFromMap(nodeToMap(n)));
        }
        List<CanvasEdge> edges = new ArrayList<>();
        if (dto.edges() != null) {
            for (CanvasEdgeDto e : dto.edges()) edges.add(CanvasCodec.edgeFromMap(edgeToMap(e)));
        }
        return new CanvasDocument(dto.title(), dto.description(), new CanvasGraph(nodes, edges));
    }

    private static Map<String, Object> nodeToMap(CanvasNodeDto n) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (n.id() != null) m.put("id", n.id());
        m.put("type", n.type());
        m.put("x", n.x());
        m.put("y", n.y());
        m.put("w", n.w());
        m.put("h", n.h());
        putIf(m, "color", n.color());
        if (n.z() != null) m.put("z", n.z());
        putIf(m, "parent", n.parent());
        putIf(m, "text", n.text());
        putIf(m, "ref", n.ref());
        putIf(m, "href", n.href());
        putIf(m, "title", n.title());
        putIf(m, "label", n.label());
        putIf(m, "bold", n.bold());
        putIf(m, "italic", n.italic());
        putIf(m, "fontSize", n.fontSize());
        putIf(m, "textColor", n.textColor());
        putIf(m, "author", n.author());
        return m;
    }

    private static Map<String, Object> edgeToMap(CanvasEdgeDto e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e.id() != null) m.put("id", e.id());
        m.put("from", e.from());
        m.put("to", e.to());
        putIf(m, "fromSide", e.fromSide());
        putIf(m, "toSide", e.toSide());
        putIf(m, "fromEnd", e.fromEnd());
        putIf(m, "toEnd", e.toEnd());
        putIf(m, "label", e.label());
        putIf(m, "color", e.color());
        putIf(m, "dashed", e.dashed());
        putIf(m, "width", e.width());
        return m;
    }

    private static void putIf(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }
}
