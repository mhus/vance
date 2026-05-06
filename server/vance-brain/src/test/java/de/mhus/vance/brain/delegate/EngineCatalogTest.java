package de.mhus.vance.brain.delegate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bundled-resource sanity test for {@link EngineCatalog}. Confirms
 * the catalog parses at boot, contains the engines we ship, and
 * renders cleanly into the selector prompt.
 */
class EngineCatalogTest {

    private EngineCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new EngineCatalog();
        catalog.load();
    }

    @Test
    void bundledCatalog_loadsEntriesInOrder() {
        assertThat(catalog.getEntries())
                .extracting(EngineCatalog.EngineEntry::name)
                .contains("arthur", "vogon", "marvin", "slartibartfast", "ford");
    }

    @Test
    void allEntriesHavePurposeAndWhenToUse() {
        for (EngineCatalog.EngineEntry e : catalog.getEntries()) {
            assertThat(e.purpose()).as("purpose for " + e.name()).isNotBlank();
            assertThat(e.whenToUse()).as("whenToUse for " + e.name()).isNotBlank();
        }
    }

    @Test
    void renderedPrompt_containsEachEngineName() {
        String rendered = catalog.renderForPrompt();
        assertThat(rendered).contains("**arthur**");
        assertThat(rendered).contains("**vogon**");
        assertThat(rendered).contains("**marvin**");
        assertThat(rendered).contains("**slartibartfast**");
        assertThat(rendered).contains("**ford**");
        assertThat(rendered).contains("when_to_use");
    }

    @Test
    void renderedPrompt_isEmptyWhenCatalogEmpty() {
        EngineCatalog empty = new EngineCatalog();
        // load() not called → entries stays empty
        assertThat(empty.renderForPrompt()).isEmpty();
    }

    @Test
    void reload_returnsParsedEntryCount_andRefreshesEntries() {
        EngineCatalog fresh = new EngineCatalog();
        // First reload populates from the bundled resource.
        int firstCount = fresh.reload();
        assertThat(firstCount).isPositive();
        assertThat(fresh.getEntries()).hasSize(firstCount);

        // Second reload is idempotent — same result.
        int secondCount = fresh.reload();
        assertThat(secondCount).isEqualTo(firstCount);
    }
}
