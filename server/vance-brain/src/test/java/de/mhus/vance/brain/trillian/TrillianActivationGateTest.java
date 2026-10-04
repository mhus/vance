package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The {@code trillian.enabled} kill switch: default-on, resolved over the
 * hub → project → tenant cascade, and read <b>without</b> the think-process
 * scope — a {@code setting_set} must not be able to switch its own gate
 * back on (same contract as {@link TrillianModelGate}).
 */
@ExtendWith(MockitoExtension.class)
class TrillianActivationGateTest {

    private static final String TENANT = "acme";
    private static final String ACCOUNT = "_trillian-void-1";
    private static final String PROJECT = "trillian-test";

    @Mock
    SettingService settingService;

    TrillianActivationGate gate;

    // Built in @BeforeEach, not in a field initializer: the mock is injected
    // after construction, so an initializer would capture null.
    @BeforeEach
    void setUp() {
        gate = new TrillianActivationGate(settingService);
    }

    @Test
    void unsetSettingMeansEnabled() {
        // The mock returns the passed default — unset must come out enabled.
        when(settingService.getBooleanValueUserProjectCascade(
                        eq(TENANT),
                        eq(ACCOUNT),
                        eq(PROJECT),
                        isNull(),
                        eq(TrillianActivationGate.ENABLED_KEY),
                        eq(true)))
                .thenReturn(true);

        assertThat(gate.loopsEnabled(TENANT, ACCOUNT, PROJECT)).isTrue();
    }

    @Test
    void falseSettingDisablesLoops() {
        when(settingService.getBooleanValueUserProjectCascade(
                        eq(TENANT),
                        eq(ACCOUNT),
                        eq(PROJECT),
                        isNull(),
                        eq(TrillianActivationGate.ENABLED_KEY),
                        eq(true)))
                .thenReturn(false);

        assertThat(gate.loopsEnabled(TENANT, ACCOUNT, PROJECT)).isFalse();
    }

    @Test
    void readsTheUserProjectCascadeWithoutThinkProcessScope() {
        when(settingService.getBooleanValueUserProjectCascade(
                        eq(TENANT), isNull(), eq(PROJECT), isNull(), eq(TrillianActivationGate.ENABLED_KEY), eq(true)))
                .thenReturn(false);

        gate.loopsEnabled(TENANT, /*accountId*/ null, PROJECT);

        // Null thinkProcessId is the self-approve guard: the gated process
        // must not reach its own gate through the think-process layer.
        verify(settingService)
                .getBooleanValueUserProjectCascade(
                        eq(TENANT), isNull(), eq(PROJECT), isNull(), eq(TrillianActivationGate.ENABLED_KEY), eq(true));
    }

    @Test
    void refusalMessageNamesTheSettingAndTheScopes() {
        assertThat(TrillianActivationGate.refusalMessage())
                .contains(TrillianActivationGate.ENABLED_KEY)
                .contains("_user_");
    }
}
