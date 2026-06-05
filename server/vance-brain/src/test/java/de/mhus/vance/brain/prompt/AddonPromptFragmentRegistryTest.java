package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the classpath-scan and per-engine ordering logic against two
 * fixture fragments shipped as test resources:
 * {@code vance-defaults/_vance/prompts/_testfragments_engine/{aaa,zzz}.md}.
 *
 * <p>The engine name {@code _testfragments_engine} is intentionally
 * impossible-looking so production code never asks for it — co-existing
 * with real fragments in the same scan is harmless.
 *
 * <p>Pebble compile failure is exercised indirectly by
 * {@link PromptTemplateRenderer}'s own tests; the registry itself only
 * delegates to {@code templateRenderer.compile} on each scanned body.
 */
class AddonPromptFragmentRegistryTest {

    private static final String TEST_ENGINE = "_testfragments_engine";

    private AddonPromptFragmentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AddonPromptFragmentRegistry(new PromptTemplateRenderer());
        registry.scan();
    }

    @Test
    void scan_picksUpTestFragmentsInAlphabeticalOrder() {
        List<AddonPromptFragment> fragments = registry.getFragments(TEST_ENGINE);

        assertThat(fragments)
                .extracting(AddonPromptFragment::addonId)
                .containsExactly("aaa", "zzz");
    }

    @Test
    void getFragments_returnsEmptyListForUnknownEngine() {
        assertThat(registry.getFragments("does-not-exist")).isEmpty();
    }

    @Test
    void getFragments_returnsEmptyListForNullOrBlankEngine() {
        assertThat(registry.getFragments(null)).isEmpty();
        assertThat(registry.getFragments("")).isEmpty();
        assertThat(registry.getFragments("   ")).isEmpty();
    }

    @Test
    void enginesWithFragments_includesTestEngine() {
        assertThat(registry.enginesWithFragments()).contains(TEST_ENGINE);
    }

    @Test
    void renderAndJoin_concatenatesFragmentsInOrderWithBlankLines() {
        PromptTemplateRenderer renderer = new PromptTemplateRenderer();
        // Test fixtures: aaa.md = "A-Fragment for tier={{ tier }}.",
        //                zzz.md = "Z-Fragment, plain markdown."
        String joined = registry.renderAndJoin(
                TEST_ENGINE, Map.of("tier", "small"), renderer);

        assertThat(joined)
                .contains("A-Fragment for tier=small.")
                .contains("Z-Fragment, plain markdown.");
        int aIdx = joined.indexOf("A-Fragment");
        int zIdx = joined.indexOf("Z-Fragment");
        assertThat(aIdx).isLessThan(zIdx);
        // Blank-line separator between fragments.
        assertThat(joined.substring(aIdx, zIdx)).contains("\n\n");
    }

    @Test
    void renderAndJoin_returnsEmptyStringForUnknownEngine() {
        PromptTemplateRenderer renderer = new PromptTemplateRenderer();
        assertThat(registry.renderAndJoin("nope", Map.of(), renderer)).isEmpty();
    }

    @Test
    void fragment_carriesEngineSourcePathAndTemplate() {
        AddonPromptFragment aaa = registry.getFragments(TEST_ENGINE).get(0);

        assertThat(aaa.engine()).isEqualTo(TEST_ENGINE);
        assertThat(aaa.addonId()).isEqualTo("aaa");
        assertThat(aaa.sourcePath())
                .endsWith("vance-defaults/_vance/prompts/"
                        + TEST_ENGINE + "/aaa.md");
        assertThat(aaa.template()).contains("A-Fragment");
    }
}
