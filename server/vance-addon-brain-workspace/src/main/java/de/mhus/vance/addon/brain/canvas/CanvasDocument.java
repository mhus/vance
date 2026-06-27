package de.mhus.vance.addon.brain.canvas;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Typed view on a {@code kind: canvas} document. Carries the structured
 * header fields ({@code title}, {@code description}) plus the parsed
 * {@link Block} list. Round-tripped via
 * {@link CanvasParser#parseDocument} /
 * {@link CanvasSerializer#serializeDocument}.
 */
public record CanvasDocument(
        @Nullable String title,
        @Nullable String description,
        List<Block> blocks) {

    public CanvasDocument {
        if (blocks == null) blocks = new ArrayList<>();
    }

    public CanvasDocument withBlocks(List<Block> newBlocks) {
        return new CanvasDocument(title, description, newBlocks);
    }
}
