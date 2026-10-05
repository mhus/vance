package de.mhus.vance.brain.fenchurch;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.image.ImageModelInfo;
import de.mhus.vance.shared.document.DocumentDocument;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the cost resolution of a successful image call:
 * the vendor-reported {@code costUsd} header (written by the dedicated
 * OpenRouter image provider from {@code usage.cost}) wins over the
 * catalog's flat {@code costPerImage} estimate, a garbage header falls
 * back to the estimate, and an unpriced call stays unpriced.
 */
class FenchurchServiceEffectiveCostTest {

    private static ImageModelInfo flatPriced(double cost) {
        return new ImageModelInfo(
                "openrouter", "seedream-5-0-flash", java.util.Set.of("1:1"), 4000, Map.of("standard", cost), 120, 0);
    }

    private static ImageModelInfo tokenPriced() {
        // No costPerImage at all — the token-priced gateway shape.
        return new ImageModelInfo(
                "openrouter", "gemini-2.5-flash-image", java.util.Set.of("1:1"), 480, Map.of(), 120, 0);
    }

    private static DocumentDocument committedWithHeaders(Map<String, String> headers) {
        DocumentDocument doc = new DocumentDocument();
        doc.setHeaders(headers);
        return doc;
    }

    @Test
    void vendor_reported_cost_wins_over_flat_estimate() {
        DocumentDocument committed = committedWithHeaders(Map.of("costUsd", "0.037"));

        assertThat(FenchurchService.effectiveCostUsd(flatPriced(0.018), committed))
                .isEqualTo(0.037);
    }

    @Test
    void token_priced_model_books_real_cost_from_header() {
        // The whole point: a model without a flat catalog price books
        // its actual spend instead of null/0.
        DocumentDocument committed = committedWithHeaders(Map.of("costUsd", "0.037"));

        assertThat(FenchurchService.effectiveCostUsd(tokenPriced(), committed)).isEqualTo(0.037);
    }

    @Test
    void missing_header_falls_back_to_flat_estimate() {
        DocumentDocument committed = committedWithHeaders(new HashMap<>());

        assertThat(FenchurchService.effectiveCostUsd(flatPriced(0.018), committed))
                .isEqualTo(0.018);
    }

    @Test
    void non_numeric_header_falls_back_to_flat_estimate() {
        DocumentDocument committed = committedWithHeaders(Map.of("costUsd", "free!!"));

        assertThat(FenchurchService.effectiveCostUsd(flatPriced(0.018), committed))
                .isEqualTo(0.018);
    }

    @Test
    void unpriced_model_stays_unpriced_without_header() {
        DocumentDocument committed = committedWithHeaders(new HashMap<>());

        assertThat(FenchurchService.effectiveCostUsd(tokenPriced(), committed)).isNull();
    }
}
