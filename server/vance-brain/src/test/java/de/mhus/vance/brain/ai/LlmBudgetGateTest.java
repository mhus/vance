package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.BudgetWindow;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmBudgetGate} — who is stopped, who is not, and who hears about it.
 */
class LlmBudgetGateTest {

    private final LlmBudgetService budgetService = mock(LlmBudgetService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final LlmBudgetGate gate = new LlmBudgetGate(budgetService, notificationService, thinkProcessService);

    private static final CallAttribution ARTHUR =
            new CallAttribution("acme", "p1", "sess-1", "proc-1", "arthur", "arthur");

    @Test
    void exhaustedBudget_refusesAPricedModel_withTheBudgetMessage() {
        when(budgetService.check("acme", "p1")).thenReturn(new LlmBudgetService.Verdict(exhausted(), List.of()));

        assertThatThrownBy(() -> gate.admit(ARTHUR, model(pricing(3.0, 15.0))))
                .isInstanceOf(LlmBudgetExceededException.class)
                .hasMessageContaining("Project 'p1' LLM budget exhausted")
                .hasMessageContaining("ai.llm.budget.daily");
    }

    @Test
    void roomLeft_admits() {
        when(budgetService.check("acme", "p1")).thenReturn(LlmBudgetService.Verdict.ALLOW);

        assertThatCode(() -> gate.admit(ARTHUR, model(pricing(3.0, 15.0)))).doesNotThrowAnyException();
    }

    @Test
    void ungatedCallers_areNeverChecked() {
        for (String caller : List.of(LlmUsageService.CALLER_COMPACTION, LlmUsageService.CALLER_TRIAGE)) {
            CallAttribution a = new CallAttribution("acme", "p1", "sess-1", "proc-1", caller, null);
            assertThatCode(() -> gate.admit(a, model(pricing(3.0, 15.0)))).doesNotThrowAnyException();
        }
        verifyNoInteractions(budgetService);
    }

    @Test
    void freeAndUnpricedModels_areNeverChecked() {
        assertThatCode(() -> gate.admit(ARTHUR, model(pricing(0.0, 0.0)))).doesNotThrowAnyException();
        assertThatCode(() -> gate.admit(ARTHUR, model(null))).doesNotThrowAnyException();
        verifyNoInteractions(budgetService);
    }

    @Test
    void aCacheRateAloneMakesAModelPriced() {
        assertThat(LlmBudgetGate.costsNothing(model(new ModelInfo.Pricing("USD", 0.0, 0.0, 0.5, null))))
                .isFalse();
    }

    @Test
    void refusal_notifiesTheCallingSessionOnce() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        when(thinkProcessService.findById("proc-1")).thenReturn(Optional.of(process));
        when(budgetService.check("acme", "p1")).thenReturn(new LlmBudgetService.Verdict(exhausted(), List.of()));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> gate.admit(ARTHUR, model(pricing(3.0, 15.0))))
                    .isInstanceOf(LlmBudgetExceededException.class);
        }

        verify(notificationService, times(1)).publish(eq(process), anyString(), eq(NotificationSeverity.ERROR));
    }

    @Test
    void warning_notifiesOnce_andAdmits() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        when(thinkProcessService.findById("proc-1")).thenReturn(Optional.of(process));
        LlmBudgetService.WindowStatus warn = window(1_000_000, 850_000);
        when(budgetService.check("acme", "p1")).thenReturn(new LlmBudgetService.Verdict(null, List.of(warn)));

        gate.admit(ARTHUR, model(pricing(3.0, 15.0)));
        gate.admit(ARTHUR, model(pricing(3.0, 15.0)));

        verify(notificationService, times(1)).publish(eq(process), anyString(), eq(NotificationSeverity.WARN));
    }

    @Test
    void processLessCall_isRefusedWithoutNotification() {
        CallAttribution light = CallAttribution.light("acme", "p1", null, "title-gen");
        when(budgetService.check("acme", "p1")).thenReturn(new LlmBudgetService.Verdict(exhausted(), List.of()));

        assertThatThrownBy(() -> gate.admit(light, model(pricing(3.0, 15.0))))
                .isInstanceOf(LlmBudgetExceededException.class);
        verify(notificationService, never()).publish(any(), anyString(), any());
    }

    @Test
    void warningText_namesLevelWindowAndPercent() {
        assertThat(LlmBudgetGate.warningText(window(1_000_000, 850_000)))
                .isEqualTo("Project 'p1' LLM budget (daily) is at 85% — calls on priced models stop at 100%.");
    }

    // ──────────────────── helpers ────────────────────

    private static LlmBudgetService.WindowStatus exhausted() {
        return window(1_000_000, 1_000_000);
    }

    private static LlmBudgetService.WindowStatus window(long limit, long used) {
        return new LlmBudgetService.WindowStatus(
                BudgetScope.PROJECT,
                "p1",
                BudgetWindow.DAILY,
                LocalDate.of(2026, 10, 16),
                limit,
                used,
                "USD",
                80,
                0,
                Map.of());
    }

    private static ModelInfo.Pricing pricing(double in, double out) {
        return new ModelInfo.Pricing("USD", in, out, null, null);
    }

    private static ModelInfo model(ModelInfo.@Nullable Pricing pricing) {
        return new ModelInfo(
                "openai",
                "kimi-k3",
                200_000,
                8192,
                ModelSize.LARGE,
                Set.<ModelCapability>of(),
                60,
                2,
                false,
                null,
                pricing,
                OutputTokenParam.MAX_TOKENS,
                Set.<SamplingParam>of(),
                null,
                null,
                false);
    }
}
