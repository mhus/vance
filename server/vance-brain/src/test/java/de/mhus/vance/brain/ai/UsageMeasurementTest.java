package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.llmusage.UsageKind;
import de.mhus.vance.shared.llmusage.UsageOutcome;
import org.junit.jupiter.api.Test;

/**
 * The flat amount of an image or audio call reaches the ledger together
 * with its currency — without it the day bucket keys the cost under an
 * empty currency.
 */
class UsageMeasurementTest {

    @Test
    void audio_carriesTheFlatAmountAndItsCurrency() {
        UsageMeasurement m =
                UsageMeasurement.audio("openrouter", "openrouter", "tts-1", 0.0123, "USD", UsageOutcome.SUCCESS, 42);

        assertThat(m.kind()).isEqualTo(UsageKind.AUDIO);
        assertThat(m.imageCost()).isEqualTo(0.0123);
        assertThat(m.resolvedCurrency()).isEqualTo("USD");
        assertThat(m.images()).isEqualTo(1);
    }

    @Test
    void image_carriesTheFlatAmountAndItsCurrency() {
        UsageMeasurement m =
                UsageMeasurement.image("openai", "openai", "gpt-image-1", 0.039, "USD", UsageOutcome.SUCCESS, 42);

        assertThat(m.resolvedCurrency()).isEqualTo("USD");
    }

    @Test
    void decision_takesTheCurrencyFromTheRateSnapshotNotTheFlatSlot() {
        UsageMeasurement m =
                UsageMeasurement.decision("openrouter", "openrouter", "jev", null, 1_000, 3, UsageOutcome.SUCCESS, 42);

        assertThat(m.resolvedCurrency()).isNull();
    }
}
