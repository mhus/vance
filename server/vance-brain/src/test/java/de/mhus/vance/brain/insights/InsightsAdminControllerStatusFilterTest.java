package de.mhus.vance.brain.insights;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.session.SessionStatus;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Parsing tests for {@link InsightsAdminController#parseStatusFilter} —
 * the {@code status} query parameter of the insights session list. The
 * presets the UI sends are comma-separated status sets ("live" view =
 * {@code INIT,RUNNING,IDLE}), so the interesting cases are mixed input,
 * unknown names and the fallback to the active view.
 */
class InsightsAdminControllerStatusFilterTest {

    @Test
    void blankOrNullFallsBackToActiveView() {
        assertThat(InsightsAdminController.parseStatusFilter(null)).isNull();
        assertThat(InsightsAdminController.parseStatusFilter("")).isNull();
        assertThat(InsightsAdminController.parseStatusFilter("   ")).isNull();
    }

    @Test
    void allSelectsEveryStatus() {
        assertThat(InsightsAdminController.parseStatusFilter("all")).containsExactlyInAnyOrder(SessionStatus.values());
        assertThat(InsightsAdminController.parseStatusFilter("ALL")).containsExactlyInAnyOrder(SessionStatus.values());
    }

    @Test
    void singleStatusNameIsAccepted() {
        assertThat(InsightsAdminController.parseStatusFilter("RUNNING")).containsExactly(SessionStatus.RUNNING);
    }

    @Test
    void commaSeparatedSetIsParsed() {
        assertThat(InsightsAdminController.parseStatusFilter("INIT,RUNNING,IDLE"))
                .containsExactlyInAnyOrder(SessionStatus.INIT, SessionStatus.RUNNING, SessionStatus.IDLE);
    }

    @Test
    void tokensAreTrimmedAndCaseInsensitive() {
        assertThat(InsightsAdminController.parseStatusFilter(" init , running "))
                .containsExactlyInAnyOrder(SessionStatus.INIT, SessionStatus.RUNNING);
    }

    @Test
    void unknownNamesAreSkipped() {
        assertThat(InsightsAdminController.parseStatusFilter("RUNNING,BOGUS,IDLE"))
                .containsExactlyInAnyOrder(SessionStatus.RUNNING, SessionStatus.IDLE);
    }

    @Test
    void allUnknownFallsBackToActiveView() {
        @Nullable Set<SessionStatus> parsed = InsightsAdminController.parseStatusFilter("BOGUS,,");
        assertThat(parsed).isNull();
    }
}
