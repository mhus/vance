package de.mhus.vance.addon.brain.canvas.model;

import java.util.List;

/**
 * The spatial graph of a canvas page — the {@code canvas:} block on
 * disk. Immutable; mutations produce a new graph.
 */
public record CanvasGraph(List<CanvasNode> nodes, List<CanvasEdge> edges) {

    public CanvasGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public static CanvasGraph empty() {
        return new CanvasGraph(List.of(), List.of());
    }
}
