package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The relaxed cost gate of the unattended loop (D8): unset means
 * everything; the allowlist cascade is account hub → the project the
 * Trillian was started in → tenant, never the think-process scope.
 */
class TrillianModelGateTest {

    private final SettingService settingService = mock(SettingService.class);
    private final AiModelResolver resolver = mock(AiModelResolver.class);
    private final TrillianModelGate gate = new TrillianModelGate(settingService, resolver);

    @BeforeEach
    void setUp() {
        AiModelResolver.Resolved resolved = new AiModelResolver.Resolved("openai", "cloud", "big-model", false);
        when(resolver.resolveOrDefault(any(), eq("acme"), any(), any())).thenReturn(resolved);
    }

    @Test
    void unsetAllowlist_approvesEverything() {
        assertThatCode(() -> gate.checkLoopModel("acme", "_trillian-void-1", "proj", "_user__trillian-void-1", null))
                .doesNotThrowAnyException();
    }

    @Test
    void aRestrictedAllowlist_refusesWithModelAndSettingNamed() {
        when(settingService.getStringValueUserProjectCascade(
                        "acme", "_trillian-void-1", "proj", null, TrillianModelGate.ALLOWED_MODELS_KEY))
                .thenReturn("selfhosted:*");

        assertThatThrownBy(
                        () -> gate.checkLoopModel("acme", "_trillian-void-1", "proj", "_user__trillian-void-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cloud:big-model")
                .hasMessageContaining(TrillianModelGate.ALLOWED_MODELS_KEY);
    }

    @Test
    void theModelResolvesWhereTheLoopRuns_theAllowlistWhereTheTrillianStarted() {
        gate.checkLoopModel("acme", "_trillian-void-1", "proj", "_user__trillian-void-1", "trillian-void:analyze");

        verify(resolver).resolveOrDefault("trillian-void:analyze", "acme", "_user__trillian-void-1", null);
        verify(settingService)
                .getStringValueUserProjectCascade(
                        "acme", "_trillian-void-1", "proj", null, TrillianModelGate.ALLOWED_MODELS_KEY);
    }
}
