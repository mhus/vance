package de.mhus.vance.addon.brain.workbook.action;

import de.mhus.vance.addon.brain.workpage.Block;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shared depth-first {@link Block.Field} traversal for the form action
 * handlers: walks a block list (descending into {@code vance-columns}),
 * lets a visitor replace fields, and rebuilds the immutable block tree only
 * where something changed.
 */
final class FieldWalk {

    /** Per-field callback; returns the replacement field or {@code null} to keep the original. */
    interface Visitor {
        Block.@Nullable Field visit(Block.Field field);
    }

    private FieldWalk() {}

    /**
     * Walk {@code blocks} in place (caller passes a fresh mutable copy).
     * Returns whether any block was replaced.
     */
    static boolean walk(List<Block> blocks, Visitor visitor) {
        boolean changed = false;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b instanceof Block.Columns cols) {
                List<Block.Column> columns = new ArrayList<>(cols.columns());
                boolean colsChanged = false;
                for (int j = 0; j < columns.size(); j++) {
                    Block.Column c = columns.get(j);
                    List<Block> inner = new ArrayList<>(c.blocks());
                    if (walk(inner, visitor)) {
                        columns.set(j, new Block.Column(c.width(), inner));
                        colsChanged = true;
                    }
                }
                if (colsChanged) {
                    blocks.set(i, new Block.Columns(columns));
                    changed = true;
                }
            } else if (b instanceof Block.Field field) {
                Block.Field replacement = visitor.visit(field);
                if (replacement != null) {
                    blocks.set(i, replacement);
                    changed = true;
                }
            }
        }
        return changed;
    }
}
