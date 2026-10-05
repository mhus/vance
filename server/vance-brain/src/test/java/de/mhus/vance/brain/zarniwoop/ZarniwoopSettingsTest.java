package de.mhus.vance.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.research.SearchModality;
import org.junit.jupiter.api.Test;

/**
 * The shipped routing chains are what a fresh installation walks before any
 * {@code research.default.*} / {@code research.fallback.*} setting exists.
 * Two properties are worth pinning: every named id follows the instance-id
 * grammar (the filename of a source document), and the WEB chain follows the
 * documented policy order — contract before keyless before built-in — because
 * that order is what makes a spent or absent paid source degrade to a free
 * one instead of to nothing.
 */
class ZarniwoopSettingsTest {

    @Test
    void setting_keys_name_the_modality_in_lowercase() {
        assertThat(ZarniwoopSettings.defaultKey(SearchModality.WEB)).isEqualTo("research.default.web");
        assertThat(ZarniwoopSettings.fallbackKey(SearchModality.ACADEMIC)).isEqualTo("research.fallback.academic");
        assertThat(ZarniwoopSettings.defaultKey(SearchModality.ENCYCLOPEDIA))
                .isEqualTo("research.default.encyclopedia");
    }

    @Test
    void shipped_chains_use_the_instance_id_grammar() {
        for (SearchModality modality : SearchModality.values()) {
            String def = ZarniwoopSettings.shippedDefault(modality);
            if (def != null) {
                assertWellFormed(def, modality);
            }
            String chain = ZarniwoopSettings.shippedFallbacks(modality);
            if (chain != null) {
                for (String id : chain.split(",")) {
                    assertWellFormed(id, modality);
                }
            }
        }
    }

    @Test
    void shipped_web_chain_orders_contract_sources_before_keyless_and_builtins() {
        String chain = ZarniwoopSettings.shippedFallbacks(SearchModality.WEB);
        assertThat(chain).isNotNull();

        int contracted = chain.indexOf("firecrawl,");
        int keyless = chain.indexOf("firecrawl-keyless");
        int selfHosted = chain.indexOf("searxng");
        int builtin = chain.indexOf("wikipedia");

        assertThat(contracted).isNotNegative();
        assertThat(keyless).isGreaterThan(contracted);
        assertThat(selfHosted).isGreaterThan(keyless);
        assertThat(builtin).isGreaterThan(selfHosted);
    }

    private static void assertWellFormed(String id, SearchModality modality) {
        assertThat(id).as("shipped routing id for %s", modality).matches("[a-z0-9][a-z0-9-]*");
    }
}
