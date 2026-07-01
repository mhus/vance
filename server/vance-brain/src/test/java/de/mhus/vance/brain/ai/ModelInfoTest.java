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
}
