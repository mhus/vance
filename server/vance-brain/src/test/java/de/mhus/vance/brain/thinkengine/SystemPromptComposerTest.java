package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.prompt.AddonPromptFragmentRegistry;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Composer-level integration test. Uses the test-only addon fixture
 * shipped in {@code src/test/resources/.../prompts/_testfragments_engine/}
 * — two fragments {@code aaa.md} and {@code zzz.md} — to assert that
 * {@link SystemPromptComposer#compose} and
 * {@link SystemPromptComposer#withAddons} merge them into the engine
 * default at the right place.
 *
 * <p>{@link SystemPrompts} blending semantics (override / mode / profile-
 * append) are exhaustively covered by {@link SystemPromptsTest}; this
 * file only proves the composer wires renderer + registry + builder
 * correctly.
 */
class SystemPromptComposerTest {

    private static final String TEST_ENGINE = "_testfragments_engine";

    private SystemPromptComposer composer;

    @BeforeEach
    void setUp() {
        PromptTemplateRenderer renderer = new PromptTemplateRenderer();
        AddonPromptFragmentRegistry registry = new AddonPromptFragmentRegistry(renderer);
        registry.scan();
        composer = new SystemPromptComposer(renderer, registry);
    }

    @Test
    void compose_inlinesAddonsAtVariablePosition() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        PromptContextBuilder builder = PromptContextBuilder.create()
                .engine(TEST_ENGINE).tier(de.mhus.vance.brain.ai.ModelSize.LARGE);

        String out = composer.compose(
                process,
                "HEAD.\n\n{{ addonSections }}\n\nTAIL.",
                builder);

        assertThat(out)
                .contains("HEAD.")
                .contains("A-Fragment for tier=large.")
                .contains("Z-Fragment, plain markdown.")
                .contains("TAIL.");
        int headIdx = out.indexOf("HEAD.");
        int aIdx = out.indexOf("A-Fragment");
        int zIdx = out.indexOf("Z-Fragment");
        int tailIdx = out.indexOf("TAIL.");
        assertThat(aIdx).isBetween(headIdx, tailIdx);
        assertThat(zIdx).isBetween(aIdx, tailIdx);
    }

    @Test
    void compose_autoAppendsWhenTemplateMissesVariable() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        PromptContextBuilder builder = PromptContextBuilder.create()
                .engine(TEST_ENGINE).tier(de.mhus.vance.brain.ai.ModelSize.SMALL);

        String out = composer.compose(process, "Engine body.", builder);

        assertThat(out)
                .contains("Engine body.")
                .contains("A-Fragment for tier=small.");
        assertThat(out.indexOf("A-Fragment"))
                .isGreaterThan(out.indexOf("Engine body."));
    }

    @Test
    void compose_engineWithoutFragments_returnsRenderedDefaultOnly() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        PromptContextBuilder builder = PromptContextBuilder.create()
                .engine("engine-without-fragments");

        String out = composer.compose(
                process, "Just the engine default.", builder);

        assertThat(out).isEqualTo("Just the engine default.");
    }

    @Test
    void withAddons_storesRenderedBlockInBuilder() {
        PromptContextBuilder builder = PromptContextBuilder.create()
                .engine(TEST_ENGINE).tier(de.mhus.vance.brain.ai.ModelSize.LARGE);

        composer.withAddons(TEST_ENGINE, builder);

        Object stored = builder.peek("addonSections");
        assertThat(stored).isInstanceOf(String.class);
        assertThat((String) stored)
                .contains("A-Fragment for tier=large.")
                .contains("Z-Fragment");
    }

    @Test
    void render_passesThroughToRenderer() {
        Map<String, Object> ctx = Map.of("name", "world");
        assertThat(composer.render("hello {{ name }}", ctx))
                .isEqualTo("hello world");
    }

    @Test
    void compose_missingEngineOnBuilder_throwsWithClearMessage() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        PromptContextBuilder builder = PromptContextBuilder.create()
                .tier(de.mhus.vance.brain.ai.ModelSize.LARGE);

        assertThatThrownBy(() ->
                composer.compose(process, "doesn't matter", builder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".engine(NAME)");
    }
}
