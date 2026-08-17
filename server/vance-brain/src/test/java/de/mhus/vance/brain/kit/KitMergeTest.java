package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Three-way merge behind the {@code merge} update policy — spec:
 * {@code planning/kit-installed-multi.md} §D7.
 */
class KitMergeTest {

    private static final String BASE = """
            # Recipe
            engine: arthur

            model: default:fast

            # Execution limits
            timeout: 30
            maxIterations: 8
            """;

    @Test
    void merge_changesOnDifferentLines_combinesBoth() {
        String ours = BASE.replace("timeout: 30", "timeout: 120");
        String theirs = BASE.replace("model: default:fast", "model: default:analyze");

        KitMerge.Result result = KitMerge.merge(BASE, ours, theirs);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.content())
                .as("the user's timeout survives and the kit's new model arrives")
                .contains("timeout: 120")
                .contains("model: default:analyze");
    }

    @Test
    void merge_bothChangedTheSameLine_conflicts() {
        String ours = BASE.replace("model: default:fast", "model: default:deep");
        String theirs = BASE.replace("model: default:fast", "model: default:analyze");

        KitMerge.Result result = KitMerge.merge(BASE, ours, theirs);

        assertThat(result.conflicted()).isTrue();
        assertThat(result.content())
                .as("conflict markers name both sides so the file is resolvable by hand")
                .contains("<<<<<<<")
                .contains(">>>>>>>")
                .contains("default:deep")
                .contains("default:analyze");
    }

    @Test
    void merge_adjacentLinesChangedOnBothSides_conflicts() {
        // Standard three-way behaviour, worth pinning down because it
        // surprises people: two edits on neighbouring lines fall into the
        // same hunk and conflict even though they do not overlap.
        String ours = BASE.replace("timeout: 30", "timeout: 120");
        String theirs = BASE.replace("maxIterations: 8", "maxIterations: 12");

        assertThat(KitMerge.merge(BASE, ours, theirs).conflicted()).isTrue();
    }

    @Test
    void merge_onlyTheKitChanged_takesTheKitVersion() {
        String theirs = BASE.replace("timeout: 30", "timeout: 60");

        KitMerge.Result result = KitMerge.merge(BASE, BASE, theirs);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.content()).contains("timeout: 60");
    }

    @Test
    void merge_onlyTheUserChanged_keepsTheUserVersion() {
        String ours = BASE.replace("timeout: 30", "timeout: 90");

        KitMerge.Result result = KitMerge.merge(BASE, ours, BASE);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.content()).contains("timeout: 90");
    }

    @Test
    void merge_identicalChangeOnBothSides_isNotAConflict() {
        // Both arrived at the same edit — nothing to reconcile.
        String same = BASE.replace("timeout: 30", "timeout: 45");

        KitMerge.Result result = KitMerge.merge(BASE, same, same);

        assertThat(result.conflicted()).isFalse();
        assertThat(result.content()).contains("timeout: 45");
    }
}
