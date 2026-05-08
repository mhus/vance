package de.mhus.vance.foot.ide;

import de.mhus.vance.api.thinkprocess.IdeContext;
import de.mhus.vance.api.thinkprocess.IdeFileRange;
import de.mhus.vance.foot.ide.dto.AtMentioned;
import de.mhus.vance.foot.ide.dto.Range;
import de.mhus.vance.foot.ide.dto.SelectionChanged;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Translates the foot's IDE-bridge state into the API-level
 * {@link IdeContext} metadata payload sent on a {@code process-steer}.
 *
 * <p>Single entry point {@link #buildAndConsumeForSteer()} that:
 * <ul>
 *   <li>drains the pending {@code at_mentioned} (single-shot — clears the
 *       slot), and</li>
 *   <li>snapshots the live editor selection (non-destructive — leaves the
 *       state holder for the next send).</li>
 * </ul>
 *
 * <p>Returns {@link Optional#empty()} when there is nothing to attach,
 * so the caller can leave {@code ProcessSteerRequest.ideContext} unset
 * and the field stays {@code null} on the wire.
 */
@Component
public class IdeContextBuilder {

    private final IdeBridgeService bridge;
    private final IdeAtMentionPending atMentionPending;
    private final IdeSelectionState selection;

    public IdeContextBuilder(IdeBridgeService bridge,
                             IdeAtMentionPending atMentionPending,
                             IdeSelectionState selection) {
        this.bridge = bridge;
        this.atMentionPending = atMentionPending;
        this.selection = selection;
    }

    public Optional<IdeContext> buildAndConsumeForSteer() {
        if (!bridge.isConnected()) {
            return Optional.empty();
        }
        @Nullable IdeFileRange atMention = atMentionPending.consume()
                .map(IdeContextBuilder::toFileRange)
                .orElse(null);
        @Nullable IdeFileRange selRange = selection.snapshot()
                .map(IdeContextBuilder::toFileRange)
                .orElse(null);
        if (atMention == null && selRange == null) {
            return Optional.empty();
        }
        return Optional.of(IdeContext.builder()
                .atMention(atMention)
                .currentSelection(selRange)
                .build());
    }

    private static IdeFileRange toFileRange(AtMentioned mention) {
        return IdeFileRange.builder()
                .filePath(mention.filePath())
                .lineStart(mention.lineStart() == null ? null : mention.lineStart() + 1)
                .lineEnd(mention.lineEnd() == null ? null : mention.lineEnd() + 1)
                .build();
    }

    private static IdeFileRange toFileRange(SelectionChanged sel) {
        Range range = sel.selection();
        IdeFileRange.IdeFileRangeBuilder b = IdeFileRange.builder()
                .filePath(sel.filePath() == null ? "" : sel.filePath());
        if (range != null) {
            int startLine = range.start().line();
            int endLine = range.end().line();
            // Plugin convention: end.character == 0 means the selection ends
            // at the start of the next line; the highlighted region is
            // start..end-1. Same adjustment as IdeSelectionState.formatRange.
            if (endLine > startLine && range.end().character() == 0) {
                endLine--;
            }
            // 1-based, inclusive.
            b.lineStart(startLine + 1).lineEnd(endLine + 1);
        }
        return b.build();
    }
}
