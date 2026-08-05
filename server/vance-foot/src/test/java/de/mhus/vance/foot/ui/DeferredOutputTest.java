package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Backlog semantics for output captured while a Lanterna excursion owns
 * the TTY (see {@link LiveRegion#pause()}).
 */
class DeferredOutputTest {

    @Test
    void drain_afterAdds_returnsEntriesInOrderAndEmptiesBacklog() {
        DeferredOutput out = new DeferredOutput(10);
        out.add("first");
        out.add("second");

        DeferredOutput.Batch batch = out.drain();

        assertThat(batch.entries()).containsExactly("first", "second");
        assertThat(batch.dropped()).isZero();
        assertThat(out.isEmpty()).isTrue();
        assertThat(out.drain().isEmpty()).isTrue();
    }

    @Test
    void add_blankText_isIgnored() {
        DeferredOutput out = new DeferredOutput(10);
        out.add(null);
        out.add("");

        assertThat(out.isEmpty()).isTrue();
    }

    @Test
    void add_pastLimit_dropsOldestAndCountsThem() {
        DeferredOutput out = new DeferredOutput(2);
        out.add("a");
        out.add("b");
        out.add("c");

        DeferredOutput.Batch batch = out.drain();

        assertThat(batch.entries()).containsExactly("b", "c");
        assertThat(batch.dropped()).isEqualTo(1);
    }

    @Test
    void drain_resetsDropCounter() {
        DeferredOutput out = new DeferredOutput(1);
        out.add("a");
        out.add("b");
        out.drain();

        out.add("c");

        assertThat(out.drain().dropped()).isZero();
    }

    @Test
    void clear_discardsBacklogAndDropCount() {
        DeferredOutput out = new DeferredOutput(1);
        out.add("a");
        out.add("b");

        out.clear();

        assertThat(out.isEmpty()).isTrue();
        assertThat(out.drain().dropped()).isZero();
    }

    @Test
    void marker_singleLine_usesSingularWording() {
        assertThat(DeferredOutput.marker(1, 0))
                .isEqualTo("— 1 line arrived while the fullscreen UI was open —");
    }

    @Test
    void marker_multipleLines_usesPluralWording() {
        assertThat(DeferredOutput.marker(12, 0))
                .isEqualTo("— 12 lines arrived while the fullscreen UI was open —");
    }

    @Test
    void marker_withDroppedLines_reportsTheLoss() {
        assertThat(DeferredOutput.marker(2000, 34))
                .isEqualTo("— 2000 lines arrived while the fullscreen UI was open"
                        + " (34 older lines dropped) —");
        assertThat(DeferredOutput.marker(2000, 1))
                .contains("(1 older line dropped)");
    }
}
