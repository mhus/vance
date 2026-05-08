package de.mhus.vance.foot.ide.dto;

import org.jspecify.annotations.Nullable;

/**
 * {@code at_mentioned} notification — the user pressed Cmd+Esc / Alt+Enter
 * "Send to Claude" in the IDE. Lines are <strong>0-based</strong> as
 * delivered by the plugin; consumers that display line numbers must add
 * {@code +1} (planning §5 tip 1).
 *
 * @param filePath  absolute path of the mentioned file
 * @param lineStart 0-based start line (inclusive). {@code null} when no
 *                  selection — the user mentioned the whole file
 * @param lineEnd   0-based end line. {@code null} when no selection
 */
public record AtMentioned(
        String filePath,
        @Nullable Integer lineStart,
        @Nullable Integer lineEnd) {
}
