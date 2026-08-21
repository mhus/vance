package de.mhus.vance.shared.starred;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The visibility ladder. Both predicates must be monotone — that is the property
 * that lets a later stage be placed by saying which two thresholds it sits
 * between, without revisiting either predicate.
 */
class StarredVisibilityTest {

    @Test
    void of_disabledWinsOverHidden() {
        // The two on-disk switches can contradict each other; enabled decides.
        assertThat(StarredVisibility.of(false, true)).isEqualTo(StarredVisibility.DISABLED);
        assertThat(StarredVisibility.of(false, false)).isEqualTo(StarredVisibility.DISABLED);
    }

    @Test
    void of_hiddenOnlyAppliesWhenEnabled() {
        assertThat(StarredVisibility.of(true, true)).isEqualTo(StarredVisibility.HIDDEN);
        assertThat(StarredVisibility.of(true, false)).isEqualTo(StarredVisibility.VISIBLE);
    }

    @Test
    void resolvable_isTrueFromHiddenUpwards() {
        assertThat(StarredVisibility.DISABLED.resolvable()).isFalse();
        assertThat(StarredVisibility.HIDDEN.resolvable()).isTrue();
        assertThat(StarredVisibility.VISIBLE.resolvable()).isTrue();
    }

    @Test
    void displayed_isTrueOnlyForVisible() {
        assertThat(StarredVisibility.DISABLED.displayed()).isFalse();
        assertThat(StarredVisibility.HIDDEN.displayed()).isFalse();
        assertThat(StarredVisibility.VISIBLE.displayed()).isTrue();
    }

    @Test
    void predicatesAreMonotoneAlongTheDeclaredOrder() {
        boolean seenResolvable = false;
        boolean seenDisplayed = false;
        for (StarredVisibility v : StarredVisibility.values()) {
            if (v.resolvable()) seenResolvable = true;
            else assertThat(seenResolvable)
                    .as("resolvable() must not go back to false at " + v)
                    .isFalse();
            if (v.displayed()) seenDisplayed = true;
            else assertThat(seenDisplayed)
                    .as("displayed() must not go back to false at " + v)
                    .isFalse();
        }
        // displayed() is the stricter threshold, so it implies resolvable().
        for (StarredVisibility v : StarredVisibility.values()) {
            if (v.displayed()) assertThat(v.resolvable()).isTrue();
        }
    }
}
