package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a {@code kind: graph} document — top-level
 * nodes + edges arrays. Edges are first-class entities with their
 * own metadata (matches the convention used by Cytoscape, GraphML,
 * vue-flow).
 *
 * @param kind   always {@code "graph"}.
 * @param graph  document-level options (directed flag).
 * @param nodes  the graph's nodes.
 * @param edges  the graph's edges.
 * @param extra  unknown top-level fields, passthrough.
 *
 * <p>Spec: {@code specification/doc-kind-graph.md}.
 */
public record GraphDocument(
        String kind,
        GraphConfig graph,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        Map<String, Object> extra) {

    public GraphDocument {
        if (kind == null || kind.isBlank()) kind = "graph";
        if (graph == null) graph = GraphConfig.defaults();
        if (nodes == null) nodes = new ArrayList<>();
        if (edges == null) edges = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static GraphDocument empty() {
        return new GraphDocument("graph", GraphConfig.defaults(),
                new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
    }
}
