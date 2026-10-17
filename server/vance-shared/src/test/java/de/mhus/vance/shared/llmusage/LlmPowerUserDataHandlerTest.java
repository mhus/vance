package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** {@link LlmPowerUserDataHandler}: the switch moves with a rename and goes with a delete. */
class LlmPowerUserDataHandlerTest {

    private static final String TENANT = "acme";

    private final LlmPowerService powerService = mock(LlmPowerService.class);
    private final LlmPowerUserDataHandler handler = new LlmPowerUserDataHandler(powerService);

    @Test
    void delegatesCountDeleteAndRenameToTheOwningService() {
        when(powerService.countUserSwitch(TENANT, "road.runner")).thenReturn(1L);
        when(powerService.deleteUserSwitch(TENANT, "road.runner")).thenReturn(1L);
        when(powerService.renameUserSwitch(TENANT, "road.runner", "rr")).thenReturn(1L);

        assertThat(handler.count(TENANT, "road.runner")).isEqualTo(1);
        assertThat(handler.delete(TENANT, "road.runner")).isEqualTo(1);
        assertThat(handler.rename(TENANT, "road.runner", "rr")).isEqualTo(1);
    }

    @Test
    void claimsNoCollection_andHasAStableId() {
        assertThat(handler.collections()).isEmpty();
        assertThat(handler.id()).isEqualTo("llm-power-user-switch");
        assertThat(handler.order())
                .isGreaterThanOrEqualTo(de.mhus.vance.shared.user.maintenance.UserDataHandler.FIRST_ORDER);
    }
}
