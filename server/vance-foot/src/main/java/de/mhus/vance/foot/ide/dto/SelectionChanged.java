package de.mhus.vance.foot.ide.dto;

import org.jspecify.annotations.Nullable;

/**
 * {@code selection_changed} notification — fired on every cursor or selection
 * change in the IDE. The plugin includes the selected source text in
 * {@link #text}, so consumers do not need to re-read the file (planning §5
 * tip 2).
 *
 * @param filePath  absolute path of the active editor; may be {@code null}
 *                  when no editor is focused
 * @param selection 0-based range of the selection. {@code null} when the
 *                  caret has no selection
 * @param text      selected source text. {@code null}/empty when no
 *                  selection
 */
public record SelectionChanged(
        @Nullable String filePath,
        @Nullable Range selection,
        @Nullable String text) {
}
