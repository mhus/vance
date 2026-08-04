package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelInfoTest {

    private static ModelInfo model(int defaultMaxOutputTokens) {
        return new ModelInfo(
                "openai", "deepseek-v3.2",
                163840, defaultMaxOutputTokens, ModelSize.LARGE,
                Set.of(), 60, 2, false, null, null);
    }

    private static ModelInfo modelWithTimeout(int timeoutSeconds) {
        return new ModelInfo(
                "openai", "deepseek-v3.2",
                163840, 8192, ModelSize.LARGE,
                Set.of(), timeoutSeconds, 2, false, null, null);
    }

    @Test
    void scaledStreamTimeout_noEstimate_fallsBackToFloor() {
        ModelInfo model = modelWithTimeout(60);

        assertThat(model.scaledStreamTimeoutSeconds(null, null))
                .isEqualTo(ModelInfo.DEFAULT_STREAM_TIMEOUT_SECONDS);
        assertThat(model.scaledStreamTimeoutSeconds(null, 0))
                .isEqualTo(ModelInfo.DEFAULT_STREAM_TIMEOUT_SECONDS);
    }

    @Test
    void scaledStreamTimeout_smallContext_staysAtFloor() {
        // 60 + 5000*4/1000 = 80 → floored to 300 (never a regression).
        assertThat(modelWithTimeout(60).scaledStreamTimeoutSeconds(null, 5_000))
                .isEqualTo(ModelInfo.DEFAULT_STREAM_TIMEOUT_SECONDS);
    }

    @Test
    void scaledStreamTimeout_largeContext_scalesUp() {
        // 60 + 70000*4/1000 = 340.
        assertThat(modelWithTimeout(60).scaledStreamTimeoutSeconds(null, 70_000))
                .isEqualTo(340);
    }

    @Test
    void scaledStreamTimeout_hugeContext_cappedAtMax() {
        assertThat(modelWithTimeout(60).scaledStreamTimeoutSeconds(null, 1_000_000))
                .isEqualTo(ModelInfo.MAX_STREAM_TIMEOUT_SECONDS);
    }

    @Test
    void scaledStreamTimeout_callerOverride_wins() {
        // Explicit override wins and ignores the estimate (floored at 300).
        assertThat(modelWithTimeout(60).scaledStreamTimeoutSeconds(600, 70_000))
                .isEqualTo(600);
    }

    @Test
    void effectiveMaxOutputTokens_noCallerOverride_usesCatalogDefault() {
        assertThat(model(8192).effectiveMaxOutputTokens(null)).isEqualTo(8192);
    }

    @Test
    void effectiveMaxOutputTokens_callerOverride_winsOverCatalogDefault() {
        assertThat(model(8192).effectiveMaxOutputTokens(2048)).isEqualTo(2048);
    }

    @Test
    void effectiveMaxOutputTokens_nonPositiveOverride_fallsBackToCatalogDefault() {
        assertThat(model(8192).effectiveMaxOutputTokens(0)).isEqualTo(8192);
    }

    @Test
    void effectiveStreamTimeout_shortSyncTimeout_liftedToStreamFloor() {
        // A 60s sync budget must not cap a streamed generation — it gets
        // the generous stream floor instead (the deepseek-v4-pro fix).
        assertThat(modelWithTimeout(60).effectiveStreamTimeoutSeconds(null))
                .isEqualTo(ModelInfo.DEFAULT_STREAM_TIMEOUT_SECONDS);
    }

    @Test
    void effectiveStreamTimeout_longSyncTimeout_winsOverFloor() {
        // A model deliberately configured slower than the floor keeps its
        // larger budget for streaming — floor is a minimum, not a cap.
        assertThat(modelWithTimeout(600).effectiveStreamTimeoutSeconds(null))
                .isEqualTo(600);
    }

    @Test
    void effectiveStreamTimeout_smallCallerOverride_liftedToFloor() {
        assertThat(modelWithTimeout(60).effectiveStreamTimeoutSeconds(30))
                .isEqualTo(ModelInfo.DEFAULT_STREAM_TIMEOUT_SECONDS);
    }

    @Test
    void effectiveStreamTimeout_largeCallerOverride_wins() {
        assertThat(modelWithTimeout(60).effectiveStreamTimeoutSeconds(450))
                .isEqualTo(450);
    }
}
