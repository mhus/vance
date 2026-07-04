package de.mhus.vance.addon.brain.canvas.model;

import org.jspecify.annotations.Nullable;

/**
 * A full {@code kind: canvas} document — display metadata plus the
 * {@link CanvasGraph}.
 */
public record CanvasDocument(
        @Nullable String title,
        @Nullable String description,
        CanvasGraph graph) {

    public static CanvasDocument empty(@Nullable String title,
                                       @Nullable String description) {
        return new CanvasDocument(title, description, CanvasGraph.empty());
    }

    public CanvasDocument withGraph(CanvasGraph newGraph) {
        return new CanvasDocument(title, description, newGraph);
    }
}
