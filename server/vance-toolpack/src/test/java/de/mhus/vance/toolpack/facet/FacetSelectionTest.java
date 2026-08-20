package de.mhus.vance.toolpack.facet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FacetSelectionTest {

    @Test
    void normalize_dropsAKeyWhoseValuesAreAllBlank() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("origin-place", List.of("  ", ""));
        raw.put("origin-topic", List.of(" gaming "));

        Map<String, List<String>> out = FacetSelection.normalize(raw);

        assertThat(out).containsExactly(Map.entry("origin-topic", List.of("gaming")));
    }

    @Test
    void normalize_dedupesValuesKeepingOrder() {
        Map<String, List<String>> out = FacetSelection.normalize(
                Map.of("origin-place", List.of("iso:SG", "m49:142", "iso:SG")));

        assertThat(out.get("origin-place")).containsExactly("iso:SG", "m49:142");
    }

    @Test
    void restrictTo_keepsOnlyDeclaredKeys() {
        Map<String, List<String>> selection = FacetSelection.normalize(Map.of(
                "origin-place", List.of("m49:142"),
                "origin-topic", List.of("gaming")));

        assertThat(FacetSelection.restrictTo(selection, Set.of("origin-place")))
                .containsOnlyKeys("origin-place");
    }

    @Test
    void undeclaredKeys_isStablyOrdered() {
        Map<String, List<String>> selection = FacetSelection.normalize(Map.of(
                "origin-topic", List.of("gaming"),
                "origin-place", List.of("m49:142"),
                "mood", List.of("calm")));

        assertThat(FacetSelection.undeclaredKeys(selection, Set.of()))
                .containsExactly("mood", "origin-place", "origin-topic");
    }

    @Test
    void facetKey_withADotIsRejected() {
        assertThatThrownBy(() -> Facet.flat("origin.place", "Origin", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'.'");
    }

    @Test
    void facet_truncatesAnOverlongInlineValueList() {
        List<FacetValue> tooMany = new ArrayList<>();
        for (int i = 0; i < Facet.MAX_INLINE_VALUES + 10; i++) {
            tooMany.add(FacetValue.of("v" + i, "Value " + i));
        }

        Facet facet = Facet.flat("origin-place", "Origin", tooMany);

        assertThat(facet.values()).hasSize(Facet.MAX_INLINE_VALUES);
    }

    @Test
    void facetValue_fallsBackToTheIdAsLabel() {
        assertThat(FacetValue.of("iso:SG", "  ").label()).isEqualTo("iso:SG");
    }
}
