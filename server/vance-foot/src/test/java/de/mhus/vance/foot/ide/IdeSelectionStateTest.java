package de.mhus.vance.foot.ide;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.ide.dto.Position;
import de.mhus.vance.foot.ide.dto.Range;
import de.mhus.vance.foot.ide.dto.SelectionChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdeSelectionStateTest {

    private IdeNotificationDispatcher dispatcher;
    private IdeSelectionState state;

    @BeforeEach
    void setUp() {
        dispatcher = new IdeNotificationDispatcher();
        state = new IdeSelectionState(dispatcher);
        state.subscribe();
    }

    @Test
    void formatRange_nullRange_returnsEmpty() {
        assertThat(IdeSelectionState.formatRange(null)).isEmpty();
    }

    @Test
    void formatRange_caretWithoutSelection_returnsEmpty() {
        Range r = new Range(new Position(4, 7), new Position(4, 7));

        assertThat(IdeSelectionState.formatRange(r)).isEmpty();
    }

    @Test
    void formatRange_singleLineSelection_returnsBracketedLine() {
        // Plugin sends 0-based; display is 1-based.
        Range r = new Range(new Position(4, 0), new Position(4, 12));

        assertThat(IdeSelectionState.formatRange(r)).isEqualTo("[5]");
    }

    @Test
    void formatRange_multiLineSelection_returnsRange() {
        Range r = new Range(new Position(4, 0), new Position(12, 5));

        assertThat(IdeSelectionState.formatRange(r)).isEqualTo("[5:13]");
    }

    @Test
    void formatRange_endCharZero_decrementsEndLine() {
        // Selection ends at start-of-line 13 — actual highlighted lines 5..12.
        Range r = new Range(new Position(4, 0), new Position(12, 0));

        assertThat(IdeSelectionState.formatRange(r)).isEqualTo("[5:12]");
    }

    @Test
    void formatRange_endCharZeroSingleLine_collapsesToSingle() {
        Range r = new Range(new Position(4, 0), new Position(5, 0));

        assertThat(IdeSelectionState.formatRange(r)).isEqualTo("[5]");
    }

    @Test
    void displayString_initiallyEmpty() {
        assertThat(state.displayString()).isEmpty();
    }

    @Test
    void onSelectionChanged_basenameOnlyWithRange() {
        SelectionChanged sel = new SelectionChanged(
                "/abs/path/foot-ui.md",
                new Range(new Position(4, 0), new Position(12, 5)),
                null);

        state.onSelectionChanged(sel);

        assertThat(state.displayString()).contains("⧉ foot-ui.md[5:13]");
    }

    @Test
    void onSelectionChanged_noRange_showsFilenameOnly() {
        SelectionChanged sel = new SelectionChanged("/abs/path/foot-ui.md", null, null);

        state.onSelectionChanged(sel);

        assertThat(state.displayString()).contains("⧉ foot-ui.md");
    }

    @Test
    void onSelectionChanged_blankFilePath_clearsState() {
        state.onSelectionChanged(new SelectionChanged("/x/y.md", null, null));
        state.onSelectionChanged(new SelectionChanged("", null, null));

        assertThat(state.displayString()).isEmpty();
    }

    @Test
    void onConnectionStateChanged_falseClears() {
        state.onSelectionChanged(new SelectionChanged("/x/y.md", null, null));
        state.onConnectionStateChanged(false);

        assertThat(state.displayString()).isEmpty();
    }

    @Test
    void onConnectionStateChanged_trueDoesNotTouchState() {
        state.onSelectionChanged(new SelectionChanged("/x/y.md", null, null));
        state.onConnectionStateChanged(true);

        assertThat(state.displayString()).isPresent();
    }

    @Test
    void repaintCallback_firesOnNewSelection() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        state.setRepaintCallback(calls::incrementAndGet);

        state.onSelectionChanged(new SelectionChanged("/x/y.md", null, null));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void repaintCallback_doesNotFireWhenDisplayUnchanged() {
        state.onSelectionChanged(new SelectionChanged("/x/y.md", null, null));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        state.setRepaintCallback(calls::incrementAndGet);

        // Same file, same (no) range → identical display string → no repaint.
        state.onSelectionChanged(new SelectionChanged("/x/y.md", null, null));

        assertThat(calls.get()).isZero();
    }

    @Test
    void repaintCallback_firesOnDisconnectWhenStateWasSet() {
        state.onSelectionChanged(new SelectionChanged("/x/y.md", null, null));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        state.setRepaintCallback(calls::incrementAndGet);

        state.onConnectionStateChanged(false);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void repaintCallback_swallowsListenerException() {
        state.setRepaintCallback(() -> { throw new RuntimeException("boom"); });

        // Must not propagate — selection events fire on the WS reader thread.
        state.onSelectionChanged(new SelectionChanged("/x/y.md", null, null));

        assertThat(state.displayString()).isPresent();
    }
}
