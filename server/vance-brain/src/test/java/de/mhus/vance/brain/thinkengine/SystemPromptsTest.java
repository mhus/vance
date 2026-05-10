package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test of the renderer-aware {@link SystemPrompts#compose}
 * overload — covers tier branching in both the engine default and the
 * recipe override, APPEND vs. OVERWRITE blending, and the
 * "no-override" fall-through. The simpler legacy overload (no renderer)
 * is exercised indirectly through the engine call sites.
 */
class SystemPromptsTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void compose_appendsRenderedOverrideToRenderedDefault() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("recipe says {{ tier }}");
        process.setPromptMode(PromptMode.APPEND);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.LARGE).engine("ford").build();

        String out = SystemPrompts.compose(
                process,
                "engine default ({{ tier }})",
                renderer, ctx);

        assertThat(out)
                .contains("engine default (large)")
                .contains("recipe says large")
                .contains("--- recipe extension ---");
    }

    @Test
    void compose_overwriteUsesOnlyRenderedOverride() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("only the override");
        process.setPromptMode(PromptMode.OVERWRITE);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.SMALL).build();

        String out = SystemPrompts.compose(
                process,
                "engine default would be discarded",
                renderer, ctx);

        assertThat(out).isEqualTo("only the override");
    }

    @Test
    void compose_tierConditionalInRecipeOverridePicksSmallBranch() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(
                "{% if tier == \"small\" %}STEP-BY-STEP{% else %}FREE-FORM{% endif %}");
        process.setPromptMode(PromptMode.APPEND);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.SMALL).build();

        String out = SystemPrompts.compose(
                process, "default", renderer, ctx);

        assertThat(out).contains("STEP-BY-STEP").doesNotContain("FREE-FORM");
    }

    @Test
    void compose_tierConditionalInEngineDefaultPicksLargeBranch() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.LARGE).build();

        String out = SystemPrompts.compose(
                process,
                "{% if tier == \"small\" %}small-default{% else %}large-default{% endif %}",
                renderer, ctx);

        assertThat(out).isEqualTo("large-default");
    }

    @Test
    void compose_modelRegexBranchesViaJinjaCompatTest() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(
                "{% if model is matching(\"gemini-.*flash.*\") %}flash-tweak{% endif %}");
        process.setPromptMode(PromptMode.OVERWRITE);
        Map<String, Object> flashCtx = PromptContextBuilder.create()
                .model("gemini-2.5-flash").build();
        Map<String, Object> opusCtx = PromptContextBuilder.create()
                .model("claude-opus-4-7").build();

        assertThat(SystemPrompts.compose(process, "", renderer, flashCtx))
                .isEqualTo("flash-tweak");
        assertThat(SystemPrompts.compose(process, "", renderer, opusCtx))
                .isEmpty();
    }

    @Test
    void compose_blankOverrideFallsBackToRenderedDefault() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("");
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.LARGE).build();

        String out = SystemPrompts.compose(
                process, "default-only", renderer, ctx);

        assertThat(out).isEqualTo("default-only");
    }

    @Test
    void compose_nullOverrideFallsBackToRenderedDefault() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.LARGE).build();

        String out = SystemPrompts.compose(
                process, "default-only", renderer, ctx);

        assertThat(out).isEqualTo("default-only");
    }

    @Test
    void compose_legacyOverloadStillWorksWithoutRendering() {
        // Legacy 2-arg path — used in places that have already-rendered
        // text, or by tests that don't care about templating. Pebble
        // syntax in the inputs is preserved verbatim.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("{{ raw }}");
        process.setPromptMode(PromptMode.APPEND);

        String out = SystemPrompts.compose(process, "default");

        assertThat(out).contains("default").contains("{{ raw }}");
    }
}
