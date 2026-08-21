package de.mhus.vance.brain.kit.provisioning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.project.ProjectActivationRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which projects the periodic check reaches.
 *
 * <p>The answer went through two wrong versions — {@code findByHomeNode(self)},
 * which was inert because ownership was broken, and a cluster-wide sweep from
 * the master pod, which reached everything including projects nobody runs.
 * With ownership fixed the question is simply "what does this pod have up",
 * which the activation registry answers without a query.
 */
class KitProvisioningCheckTickTest {

    private KitProvisioningProperties properties;
    private KitProvisioningCheck check;
    private ProjectActivationRegistry activationRegistry;
    private KitProvisioningCheckTick tick;

    @BeforeEach
    void setUp() {
        properties = new KitProvisioningProperties();
        check = mock(KitProvisioningCheck.class);
        activationRegistry = new ProjectActivationRegistry();
        tick = new KitProvisioningCheckTick(properties, check, activationRegistry);
        when(check.check(any(), any()))
                .thenReturn(new KitProvisioningCheck.Report(List.of(), List.of(), List.of()));
    }

    @Test
    void checksEveryProjectThisPodHasUp() {
        activationRegistry.activate("acme", "mail-assistant");
        activationRegistry.activate("acme", "test1");

        tick.tick();

        verify(check, times(1)).check("acme", "mail-assistant");
        verify(check, times(1)).check("acme", "test1");
    }

    @Test
    void dormantProjectsAreNotChecked() {
        // An available kit update for a project nobody is running is not news,
        // and the project gets provisioned when it next comes up.
        tick.tick();

        verify(check, never()).check(any(), any());
    }

    @Test
    void podlessProjectsAreChecked_becauseThisPodIsRunningThem() {
        // Podless projects hold no lease but are genuinely active here, and
        // the registry is about activation, not ownership.
        activationRegistry.activate("acme", "_user_marvin");

        tick.tick();

        verify(check, times(1)).check("acme", "_user_marvin");
    }

    @Test
    void oneBrokenProjectDoesNotEndTheSweep() {
        activationRegistry.activate("acme", "broken");
        activationRegistry.activate("acme", "healthy");
        when(check.check("acme", "broken")).thenThrow(new IllegalStateException("bad yaml"));

        tick.tick();

        verify(check, times(1)).check("acme", "healthy");
    }

    @Test
    void disabledByConfiguration_doesNothing() {
        activationRegistry.activate("acme", "mail-assistant");
        properties.setCheckEnabled(false);

        tick.tick();

        verify(check, never()).check(any(), any());
    }
}
