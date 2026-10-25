package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;

class ProjectQuotaServiceTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final SettingService settingService = mock(SettingService.class);
    private final ProjectQuotaService service = new ProjectQuotaService(projectService, settingService);

    private void givenLimit(int limit) {
        when(settingService.getIntValue(
                        "acme",
                        SettingService.SCOPE_PROJECT,
                        ProjectService.TENANT_PROJECT_NAME,
                        ProjectQuotaService.KEY_MAX_ACTIVE,
                        0))
                .thenReturn(limit);
    }

    @Test
    void noLimit_allowsWithoutCounting() {
        givenLimit(0);

        assertThatCode(() -> service.checkOneMore("acme")).doesNotThrowAnyException();
        verify(projectService, never()).countActive(anyString());
    }

    @Test
    void negativeLimit_isNoLimit() {
        givenLimit(-3);

        assertThatCode(() -> service.checkOneMore("acme")).doesNotThrowAnyException();
        assertThat(service.status("acme").limit()).isZero();
    }

    @Test
    void belowLimit_allows() {
        givenLimit(3);
        when(projectService.countActive("acme")).thenReturn(2L);

        assertThatCode(() -> service.checkOneMore("acme")).doesNotThrowAnyException();
    }

    @Test
    void atLimit_refusesWithCountAndLimit() {
        givenLimit(3);
        when(projectService.countActive("acme")).thenReturn(3L);

        assertThatThrownBy(() -> service.checkOneMore("acme"))
                .isInstanceOfSatisfying(ProjectQuotaService.ProjectQuotaExceededException.class, e -> {
                    assertThat(e.active()).isEqualTo(3L);
                    assertThat(e.limit()).isEqualTo(3);
                });
    }

    @Test
    void status_reportsCountAndLimit() {
        givenLimit(10);
        when(projectService.countActive("acme")).thenReturn(7L);

        assertThat(service.status("acme")).isEqualTo(new ProjectQuotaService.Status(7L, 10));
    }
}
