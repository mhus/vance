package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.addon.brain.canvas.model.CanvasEdge;
import de.mhus.vance.addon.brain.canvas.model.CanvasGraph;
import de.mhus.vance.addon.brain.canvas.model.CanvasNode;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * High-level operations on {@code kind: canvas} documents — built on top
 * of {@link DocumentService} (no MongoDB collections of its own).
 *
 * <p>Every mutation is a read-modify-write through {@link CanvasCodec}.
 * Concurrent edits resolve via {@link DocumentService}'s optimistic
 * locking, as with every other document kind.
 *
 * <p>Node/edge {@code id}s are minted per document as {@code n1, n2, …}
 * / {@code e1, e2, …} — short, collision-free within the graph, and
 * deterministic (no randomness) so results are reproducible.
 */
@Service
@Slf4j
public class CanvasService {

    public static final String KIND = "canvas";
    private static final String DEFAULT_MIME = "application/yaml";

    private final DocumentService documentService;

    public CanvasService(DocumentService documentService) {
        this.documentService = documentService;
    }

    // ── Create / read / write ─────────────────────────────────────

    public DocumentDocument create(String tenantId, String projectId, String path,
                                   @Nullable String title,
                                   @Nullable String description,
                                   @Nullable String userId) {
        String normalisedPath = ensureExtension(path.trim());
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, normalisedPath);
        if (existing.isPresent()) {
            throw new ToolException("Canvas already exists at '" + normalisedPath + "'.");
        }
        String mime = mimeForPath(normalisedPath);
        CanvasDocument canvas = CanvasDocument.empty(title, description);
        String body = CanvasCodec.serialize(canvas, mime);
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            DocumentDocument stored = documentService.create(
                    tenantId, projectId, normalisedPath,
                    title, List.of("canvas"), mime, in, userId);
            log.info("CanvasService.create tenant='{}' project='{}' path='{}'",
                    tenantId, projectId, normalisedPath);
            return stored;
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write canvas '" + normalisedPath + "': " + e.getMessage());
        }
    }

    public CanvasDocument readDocument(DocumentDocument doc) {
        String body = readBody(doc);
        String mime = CanvasCodec.supports(doc.getMimeType()) ? doc.getMimeType() : DEFAULT_MIME;
        return CanvasCodec.parse(body, mime);
    }

    public DocumentDocument writeDocument(DocumentDocument doc, CanvasDocument canvas) {
        String mime = CanvasCodec.supports(doc.getMimeType()) ? doc.getMimeType() : DEFAULT_MIME;
        String body = CanvasCodec.serialize(canvas, mime);
        return documentService.update(
                doc.getId(),
                canvas.title() != null ? canvas.title() : doc.getTitle(),
                null, body, null, null, null, null, mime);
    }

    private String readBody(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not load canvas '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    // ── Node operations ───────────────────────────────────────────

    /** Result of a node/edge mutation: the created/affected id + updated doc. */
    public record MutationResult(String id, DocumentDocument doc) {}

    public MutationResult addNode(DocumentDocument doc, Map<String, Object> raw) {
        CanvasDocument canvas = readDocument(doc);
        List<CanvasNode> nodes = new ArrayList<>(canvas.graph().nodes());

        Map<String, Object> spec = new LinkedHashMap<>(raw);
        String id = strOrNull(spec.get("id"));
        if (id == null) {
            id = nextId(idsOf(nodes), "n");
        } else if (nodeById(nodes, id) != null) {
            throw new ToolException("Node id '" + id + "' already exists.");
        }
        spec.put("id", id);

        CanvasNode node = CanvasCodec.nodeFromMap(spec);
        nodes.add(node);
        DocumentDocument updated = writeDocument(doc,
                canvas.withGraph(new CanvasGraph(nodes, canvas.graph().edges())));
        return new MutationResult(id, updated);
    }

    public MutationResult updateNode(DocumentDocument doc, String nodeId,
                                     Map<String, Object> patch) {
        CanvasDocument canvas = readDocument(doc);
        List<CanvasNode> nodes = new ArrayList<>(canvas.graph().nodes());
        int pos = indexOfNode(nodes, nodeId);
        if (pos < 0) throw new ToolException("No node with id '" + nodeId + "'.");

        Map<String, Object> merged = CanvasCodec.nodeToMap(nodes.get(pos));
        merged.putAll(patch);
        merged.put("id", nodeId); // id is immutable
        CanvasNode updatedNode = CanvasCodec.nodeFromMap(merged);
        nodes.set(pos, updatedNode);

        DocumentDocument updated = writeDocument(doc,
                canvas.withGraph(new CanvasGraph(nodes, canvas.graph().edges())));
        return new MutationResult(nodeId, updated);
    }

    public MutationResult deleteNode(DocumentDocument doc, String nodeId) {
        CanvasDocument canvas = readDocument(doc);
        List<CanvasNode> nodes = new ArrayList<>(canvas.graph().nodes());
        int pos = indexOfNode(nodes, nodeId);
        if (pos < 0) throw new ToolException("No node with id '" + nodeId + "'.");
        nodes.remove(pos);

        // Drop incident edges — a dangling edge would be an invalid graph.
        List<CanvasEdge> edges = new ArrayList<>();
        for (CanvasEdge e : canvas.graph().edges()) {
            if (!e.from().equals(nodeId) && !e.to().equals(nodeId)) edges.add(e);
        }
        DocumentDocument updated = writeDocument(doc,
                canvas.withGraph(new CanvasGraph(nodes, edges)));
        return new MutationResult(nodeId, updated);
    }

    // ── Edge operations ───────────────────────────────────────────

    public MutationResult addEdge(DocumentDocument doc, Map<String, Object> raw) {
        CanvasDocument canvas = readDocument(doc);
        List<CanvasNode> nodes = canvas.graph().nodes();
        List<CanvasEdge> edges = new ArrayList<>(canvas.graph().edges());

        Map<String, Object> spec = new LinkedHashMap<>(raw);
        String id = strOrNull(spec.get("id"));
        if (id == null) {
            id = nextId(idsOf(edges), "e");
        } else if (edgeById(edges, id) != null) {
            throw new ToolException("Edge id '" + id + "' already exists.");
        }
        spec.put("id", id);

        CanvasEdge edge = CanvasCodec.edgeFromMap(spec);
        if (nodeById(nodes, edge.from()) == null) {
            throw new ToolException("Edge `from` node '" + edge.from() + "' does not exist.");
        }
        if (nodeById(nodes, edge.to()) == null) {
            throw new ToolException("Edge `to` node '" + edge.to() + "' does not exist.");
        }
        edges.add(edge);
        DocumentDocument updated = writeDocument(doc,
                canvas.withGraph(new CanvasGraph(nodes, edges)));
        return new MutationResult(id, updated);
    }

    public MutationResult deleteEdge(DocumentDocument doc, String edgeId) {
        CanvasDocument canvas = readDocument(doc);
        List<CanvasEdge> edges = new ArrayList<>(canvas.graph().edges());
        boolean removed = edges.removeIf(e -> e.id().equals(edgeId));
        if (!removed) throw new ToolException("No edge with id '" + edgeId + "'.");
        DocumentDocument updated = writeDocument(doc,
                canvas.withGraph(new CanvasGraph(canvas.graph().nodes(), edges)));
        return new MutationResult(edgeId, updated);
    }

    // ── Query ─────────────────────────────────────────────────────

    /**
     * Filter the node list. {@code typeFilter} (one of
     * {@code text/doc/link/group}, case-insensitive) and
     * {@code textContains} are AND-combined; both may be {@code null}.
     * Returns node maps in {@link CanvasCodec#nodeToMap} shape.
     */
    public List<Map<String, Object>> query(DocumentDocument doc,
                                            @Nullable String typeFilter,
                                            @Nullable String textContains) {
        CanvasDocument canvas = readDocument(doc);
        String needle = textContains == null ? null : textContains.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CanvasNode n : canvas.graph().nodes()) {
            if (typeFilter != null && !n.type().equalsIgnoreCase(typeFilter)) continue;
            if (needle != null && !nodeText(n).toLowerCase(Locale.ROOT).contains(needle)) continue;
            out.add(CanvasCodec.nodeToMap(n));
        }
        return out;
    }

    // ── Pure helpers ──────────────────────────────────────────────

    /** Mint the next {@code prefix + N} id not colliding with {@code existing}. */
    public static String nextId(Collection<String> existing, String prefix) {
        int max = 0;
        for (String id : existing) {
            if (id != null && id.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    /* non-numeric suffix — ignore for max computation */
                }
            }
        }
        return prefix + (max + 1);
    }

    public static String nodeText(CanvasNode n) {
        return switch (n) {
            case CanvasNode.Text t -> t.text();
            case CanvasNode.Doc d -> d.ref();
            case CanvasNode.Link l -> l.href() + (l.title() == null ? "" : " " + l.title());
            case CanvasNode.Group g -> g.label() == null ? "" : g.label();
        };
    }

    private static List<String> idsOf(List<? extends Object> items) {
        List<String> ids = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof CanvasNode n) ids.add(n.id());
            else if (o instanceof CanvasEdge e) ids.add(e.id());
        }
        return ids;
    }

    private static @Nullable CanvasNode nodeById(List<CanvasNode> nodes, String id) {
        for (CanvasNode n : nodes) if (n.id().equals(id)) return n;
        return null;
    }

    private static int indexOfNode(List<CanvasNode> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id().equals(id)) return i;
        return -1;
    }

    private static @Nullable CanvasEdge edgeById(List<CanvasEdge> edges, String id) {
        for (CanvasEdge e : edges) if (e.id().equals(id)) return e;
        return null;
    }

    private static @Nullable String strOrNull(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String ensureExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json")) {
            return path;
        }
        if (lower.endsWith(".canvas")) return path + ".yaml";
        return path + ".canvas.yaml";
    }

    private static String mimeForPath(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".json") ? "application/json" : DEFAULT_MIME;
    }
}
