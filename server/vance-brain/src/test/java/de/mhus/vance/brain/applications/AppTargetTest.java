package de.mhus.vance.brain.applications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.applications.VanceApplication.AppTarget;
import de.mhus.vance.brain.applications.VanceApplication.TargetPurpose;
import de.mhus.vance.brain.applications.VanceApplication.TargetsContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The handle is the part that travels: it goes into a stored link and into the
 * Milliways share value. These tests pin the grammar at the one place that
 * produces handles, so no consumer has to escape them.
 */
class AppTargetTest {

    @Test
    void appTarget_pipeInHandle_rejected() {
        // The Milliways app-share value is `project|path[|handle]` — a handle
        // carrying the separator would split into the wrong pieces there.
        assertThatThrownBy(() -> new AppTarget("pages|intro", "Intro", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("|");
    }

    @Test
    void appTarget_blankHandle_rejected() {
        assertThatThrownBy(() -> new AppTarget("  ", "Intro", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handle");
    }

    @Test
    void appTarget_blankLabel_rejected() {
        // A place nobody can read is not pickable — and the handle goes into
        // the message so the offending app is identifiable.
        assertThatThrownBy(() -> new AppTarget("intro", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intro");
    }

    @Test
    void appTarget_slashesAndSpaces_accepted() {
        // Wiki addresses its pages by space-qualified slug, Workbook by
        // document id — the grammar must not favour either.
        AppTarget target = new AppTarget("ops/deploys", "Deploys", "ops");

        assertThat(target.handle()).isEqualTo("ops/deploys");
        assertThat(target.group()).isEqualTo("ops");
    }

    @Test
    void appTarget_of_leavesGroupUnset() {
        assertThat(AppTarget.of("intro", "Intro").group()).isNull();
    }

    @Test
    void targets_notOverridden_isEmptyForEveryPurpose() {
        // Opt-in contract: an app that knows nothing about places must not have
        // to say so, and must not accidentally offer any.
        VanceApplication app = new VanceApplication() {
            @Override
            public String appName() {
                return "test";
            }

            @Override
            public RefreshResult refresh(RefreshContext ctx) {
                return new RefreshResult("test", ctx.folder(), List.of());
            }
        };

        for (TargetPurpose purpose : TargetPurpose.values()) {
            assertThat(app.targets(new TargetsContext(
                    "acme", "work", "notes", null, purpose, Map.of())))
                    .as("purpose=%s", purpose)
                    .isEmpty();
        }
    }
}
