package de.mhus.vance.addon.brain.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.applications.VanceApplication.AppStatus;
import de.mhus.vance.brain.applications.VanceApplication.StatusSeverity;
import org.junit.jupiter.api.Test;

/** An app that reports no severity (or no lists) is not an error on the desktop card. */
class DesktopMapperTest {

    @Test
    void aStatusWithoutSeverityReadsAsOk() {
        DesktopStatusView view = DesktopMapper.toView(new AppStatus("Nothing to do", null, null, null, null));

        assertThat(view.getSeverity()).isEqualTo("ok");
        assertThat(view.getHeadline()).isEqualTo("Nothing to do");
        assertThat(view.getMetrics()).isEmpty();
        assertThat(view.getItems()).isEmpty();
    }

    @Test
    void aGivenSeverityIsKept() {
        DesktopStatusView view =
                DesktopMapper.toView(new AppStatus("Late", StatusSeverity.ATTENTION, null, null, null));

        assertThat(view.getSeverity()).isEqualTo("attention");
    }
}
