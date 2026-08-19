package de.mhus.vance.addon.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A manifest is a file a person edits, so every test here is about the same
 * question: does a mistake in that file cost one value or the whole app?
 */
class SearchConfigTest {

    @Test
    void from_readsTheBlock() {
        SearchConfig config = SearchConfig.from(manifest(Map.of(
                "defaultModality", "academic",
                "defaultNum", 8)));

        assertThat(config.defaultModality()).isEqualTo(SearchModality.ACADEMIC);
        assertThat(config.defaultNum()).isEqualTo(8);
    }

    @Test
    void from_missingBlockYieldsDefaults() {
        assertThat(SearchConfig.from(manifest(null)).defaultModality())
                .isEqualTo(SearchConfig.FALLBACK_MODALITY);
        assertThat(SearchConfig.from(manifest(null)).defaultNum())
                .isEqualTo(SearchConfig.FALLBACK_NUM);
    }

    @Test
    void from_unknownModalityFallsBackRatherThanThrowing() {
        // A typo in a hand-edited manifest must not take down the app you would
        // fix the typo in.
        SearchConfig config = SearchConfig.from(manifest(Map.of("defaultModality", "acdemic")));

        assertThat(config.defaultModality()).isEqualTo(SearchConfig.FALLBACK_MODALITY);
    }

    @Test
    void from_readsANumberWrittenAsAString() {
        // YAML gives you either, depending on quoting.
        assertThat(SearchConfig.from(manifest(Map.of("defaultNum", "7"))).defaultNum())
                .isEqualTo(7);
    }

    @Test
    void from_nonNumericCountFallsBack() {
        assertThat(SearchConfig.from(manifest(Map.of("defaultNum", "many"))).defaultNum())
                .isEqualTo(SearchConfig.FALLBACK_NUM);
    }

    @Test
    void from_readsSavedSearches() {
        SearchConfig config = SearchConfig.from(manifest(Map.of(
                "savedSearches", List.of(
                        Map.of("name", "Tariffs", "query", "tariffs 2026",
                                "modality", "news"),
                        Map.of("name", "Papers", "query", "graph neural",
                                "modality", "academic", "tier", "expert",
                                "instance", "openalex")))));

        assertThat(config.savedSearches()).hasSize(2);
        assertThat(config.savedSearches().get(0).modality()).isEqualTo(SearchModality.NEWS);
        assertThat(config.savedSearches().get(0).tier()).isEqualTo(SearchTier.NORMAL);
        assertThat(config.savedSearches().get(1).tier()).isEqualTo(SearchTier.EXPERT);
        assertThat(config.savedSearches().get(1).instance()).isEqualTo("openalex");
    }

    @Test
    void from_skipsASavedSearchWithoutAQueryRatherThanRefusingTheManifest() {
        // Losing one row beats losing the rows that were fine.
        SearchConfig config = SearchConfig.from(manifest(Map.of(
                "savedSearches", List.of(
                        Map.of("name", "Broken"),
                        Map.of("name", "Fine", "query", "something")))));

        assertThat(config.savedSearches()).singleElement()
                .satisfies(s -> assertThat(s.name()).isEqualTo("Fine"));
    }

    @Test
    void toBlock_roundTrips() {
        SearchConfig original = new SearchConfig(SearchModality.NEWS, 9,
                List.of(new SearchConfig.SavedSearch("Tariffs", "tariffs",
                        SearchModality.NEWS, SearchTier.EXPERT, "serper-main")));

        SearchConfig round = SearchConfig.from(manifest(original.toBlock()));

        assertThat(round).isEqualTo(original);
    }

    @Test
    void toBlock_omitsTheDefaultTierAndAnAbsentInstance() {
        // The manifest is read by people; writing `tier: normal` on every row
        // adds noise that carries no information.
        SearchConfig config = new SearchConfig(SearchModality.WEB, 5,
                List.of(new SearchConfig.SavedSearch("A", "q",
                        SearchModality.WEB, SearchTier.NORMAL, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> saved =
                (List<Map<String, Object>>) config.toBlock().get("savedSearches");

        assertThat(saved).singleElement().satisfies(row ->
                assertThat(row).doesNotContainKey("tier").doesNotContainKey("instance"));
    }

    @Test
    void toBlock_hasNoSavedSearchesKeyWhenThereAreNone() {
        assertThat(SearchConfig.empty().toBlock()).doesNotContainKey("savedSearches");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static ApplicationDocument manifest(Map<String, Object> block) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (block != null) {
            config.put(SearchConfig.BLOCK, block);
        }
        return new ApplicationDocument("application", SearchApplication.APP_NAME,
                "Search", null, config, new LinkedHashMap<>());
    }
}
