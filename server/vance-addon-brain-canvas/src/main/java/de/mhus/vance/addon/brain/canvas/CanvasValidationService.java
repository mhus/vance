package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.addon.brain.canvas.model.CanvasEdge;
import de.mhus.vance.addon.brain.canvas.model.CanvasNode;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Static structural check of a {@code kind: canvas} document — the
 * canvas counterpart of {@code workbook_validate}. Lets an LLM verify a
 * board it just generated (dangling edges, duplicate ids, bad group
 * references, …) before declaring it done. Read-only.
 */
@Service
public class CanvasValidationService {

    private static final String DEFAULT_MIME = "application/yaml";

    private final DocumentService documentService;

    public CanvasValidationService(DocumentService documentService) {
        this.documentService = documentService;
    }

    public enum Level { ERROR, WARNING }

    public record Finding(Level level, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level.name().toLowerCase(Locale.ROOT));
            m.put("message", message);
            return m;
        }
    }

    public record Result(String target, boolean ok, List<Finding> findings) {
        public Map<String, Object> toMap() {
            long errors = findings.stream().filter(f -> f.level() == Level.ERROR).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("target", target);
            m.put("ok", ok);
            m.put("errors", errors);
            m.put("warnings", findings.size() - errors);
            m.put("findings", findings.stream().map(Finding::toMap).toList());
            return m;
        }
    }

    public Result validate(String tenantId, String projectId, String path) {
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No canvas at '" + path + "'."));
        if (!CanvasService.KIND.equals(doc.getKind())) {
            throw new ToolException("Document '" + path + "' is not a canvas (kind="
                    + doc.getKind() + ").");
        }
        String body;
        try (InputStream in = documentService.loadContent(doc)) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not read '" + path + "': " + e.getMessage());
        }
        String mime = CanvasCodec.supports(doc.getMimeType()) ? doc.getMimeType() : DEFAULT_MIME;

        List<Finding> findings = new ArrayList<>();
        CanvasDocument canvas;
        try {
            canvas = CanvasCodec.parse(body, mime);
        } catch (RuntimeException e) {
            findings.add(new Finding(Level.ERROR, "Parse error: " + e.getMessage()));
            return new Result(path, false, findings);
        }
        findings.addAll(validateGraph(canvas));
        boolean ok = findings.stream().noneMatch(f -> f.level() == Level.ERROR);
        return new Result(path, ok, findings);
    }

    /** Pure structural checks over a parsed graph — no I/O, unit-testable. */
    public static List<Finding> validateGraph(CanvasDocument doc) {
        List<Finding> out = new ArrayList<>();
        List<CanvasNode> nodes = doc.graph().nodes();
        List<CanvasEdge> edges = doc.graph().edges();

        Set<String> nodeIds = new HashSet<>();
        Set<String> groupIds = new HashSet<>();
        for (CanvasNode n : nodes) {
            if (!nodeIds.add(n.id())) {
                out.add(new Finding(Level.ERROR, "Duplicate node id '" + n.id() + "'."));
            }
            if (n instanceof CanvasNode.Group) groupIds.add(n.id());
        }

        Set<String> edgeIds = new HashSet<>();
        for (CanvasEdge e : edges) {
            if (!edgeIds.add(e.id())) {
                out.add(new Finding(Level.ERROR, "Duplicate edge id '" + e.id() + "'."));
            }
            if (!nodeIds.contains(e.from())) {
                out.add(new Finding(Level.ERROR,
                        "Edge '" + e.id() + "' references unknown source node '" + e.from() + "'."));
            }
            if (!nodeIds.contains(e.to())) {
                out.add(new Finding(Level.ERROR,
                        "Edge '" + e.id() + "' references unknown target node '" + e.to() + "'."));
            }
        }

        for (CanvasNode n : nodes) {
            String p = n.parent();
            if (p != null) {
                if (p.equals(n.id())) {
                    out.add(new Finding(Level.ERROR, "Node '" + n.id() + "' is its own parent."));
                } else if (!nodeIds.contains(p)) {
                    out.add(new Finding(Level.ERROR,
                            "Node '" + n.id() + "' has unknown parent '" + p + "'."));
                } else if (!groupIds.contains(p)) {
                    out.add(new Finding(Level.ERROR,
                            "Node '" + n.id() + "' parent '" + p + "' is not a group."));
                }
                if (n instanceof CanvasNode.Group) {
                    out.add(new Finding(Level.WARNING, "Group '" + n.id()
                            + "' is nested inside another group — v1 groups should be top-level."));
                }
            }
            if (n instanceof CanvasNode.Text t && t.text().isBlank()) {
                out.add(new Finding(Level.WARNING, "Text node '" + n.id() + "' has empty text."));
            }
        }
        return out;
    }
}
