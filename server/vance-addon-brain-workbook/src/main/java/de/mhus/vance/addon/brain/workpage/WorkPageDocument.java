package de.mhus.vance.addon.brain.workpage;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Typed view on a {@code kind: workpage} document. Carries the structured
 * header fields ({@code title}, {@code description}) plus the parsed
 * {@link Block} list. Round-tripped via
 * {@link WorkPageParser#parseDocument} /
 * {@link WorkPageSerializer#serializeDocument}.
 */
public record WorkPageDocument(
        @Nullable String title,
        @Nullable String description,
        List<Block> blocks) {

    public WorkPageDocument {
        if (blocks == null) blocks = new ArrayList<>();
    }

    public WorkPageDocument withBlocks(List<Block> newBlocks) {
        return new WorkPageDocument(title, description, newBlocks);
    }
}
