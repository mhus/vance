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
    void compose_autoAppendsProfileAppendWhenTemplateDoesNotReferenceVariable() {
        // Recipe template doesn't mention {{ profileAppend }} → legacy
        // behaviour: profile-append is glued to the end of the rendered
        // override.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("Recipe rules.");
        process.setPromptOverrideAppend("Profile note.");
        process.setPromptMode(PromptMode.OVERWRITE);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.LARGE).build();

        String out = SystemPrompts.compose(
                process, "engine default", renderer, ctx);

        assertThat(out).contains("Recipe rules.").contains("Profile note.");
        // Auto-appended → split with the standard double-newline separator.
        int recipeIdx = out.indexOf("Recipe rules.");
        int profileIdx = out.indexOf("Profile note.");
        assertThat(profileIdx).isGreaterThan(recipeIdx);
    }

    @Test
    void compose_skipsAutoAppendWhenTemplateReferencesProfileAppendVariable() {
        // Recipe template explicitly places {{ profileAppend }} →
        // the renderer must NOT auto-append at the end.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(
                "Top.\n\n[client] {{ profileAppend }}\n\nBottom.");
        process.setPromptOverrideAppend("Profile note.");
        process.setPromptMode(PromptMode.OVERWRITE);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .tier(ModelSize.LARGE).build();

        String out = SystemPrompts.compose(
                process, "engine default", renderer, ctx);

        assertThat(out)
                .contains("Top.")
                .contains("[client] Profile note.")
                .contains("Bottom.");
        // The string "Profile note." should appear EXACTLY ONCE — no
        // duplicate auto-append at the end.
        assertThat(out.indexOf("Profile note."))
                .isEqualTo(out.lastIndexOf("Profile note."));
    }

    @Test
    void compose_profileAppendVariableRendersItsOwnPebbleSyntax() {
        // The profile-append is itself a Pebble template, so its body
        // can branch on tier/profile too. The renderer evaluates it
        // first and exposes the result as {{ profileAppend }}.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("Body. {{ profileAppend }}");
        process.setPromptOverrideAppend(
                "{% if profile == \"foot\" %}foot-note{% else %}other-note{% endif %}");
        process.setPromptMode(PromptMode.OVERWRITE);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .profile("foot").build();

        String out = SystemPrompts.compose(
                process, "engine default", renderer, ctx);

        assertThat(out).isEqualTo("Body. foot-note");
    }

    @Test
    void compose_blankProfileAppendSkipsAutoAppendEvenWhenVariableMissing() {
        // No profile-append at all → no auto-append, regardless of
        // whether the template references the variable.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("Just the recipe.");
        process.setPromptOverrideAppend(null);
        process.setPromptMode(PromptMode.OVERWRITE);
        Map<String, Object> ctx = PromptContextBuilder.create().build();

        String out = SystemPrompts.compose(
                process, "engine default", renderer, ctx);

        assertThat(out).isEqualTo("Just the recipe.");
    }

    @Test
    void compose_appendsProfileAppendToEngineDefaultWhenNoOverride() {
        // No recipe override at all → engine default + profile-append.
        // Engine default doesn't get scanned for the variable since
        // it's not the recipe's template.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        process.setPromptOverrideAppend("Profile-only note.");
        Map<String, Object> ctx = PromptContextBuilder.create().build();

        String out = SystemPrompts.compose(
                process, "engine default", renderer, ctx);

        assertThat(out)
                .contains("engine default")
                .contains("Profile-only note.");
    }

    @Test
    void compose_addonSections_placedExplicitlyViaPebbleVariable() {
        // Engine default references {{ addonSections }} → block lands
        // exactly there, no auto-append at the tail.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .addonSections("ADDON-BLOCK").build();

        String out = SystemPrompts.compose(
                process,
                "HEAD.\n\n{{ addonSections }}\n\nTAIL.",
                renderer, ctx);

        assertThat(out).contains("HEAD.").contains("ADDON-BLOCK").contains("TAIL.");
        int headIdx = out.indexOf("HEAD.");
        int addonIdx = out.indexOf("ADDON-BLOCK");
        int tailIdx = out.indexOf("TAIL.");
        assertThat(addonIdx).isBetween(headIdx, tailIdx);
        // Single occurrence — no auto-append duplicate.
        assertThat(out.indexOf("ADDON-BLOCK"))
                .isEqualTo(out.lastIndexOf("ADDON-BLOCK"));
    }

    @Test
    void compose_addonSections_autoAppendedWhenEngineDefaultMissesVariable() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .addonSections("ADDON-BLOCK").build();

        String out = SystemPrompts.compose(
                process, "ENGINE-BODY", renderer, ctx);

        assertThat(out).contains("ENGINE-BODY").contains("ADDON-BLOCK");
        assertThat(out.indexOf("ADDON-BLOCK"))
                .isGreaterThan(out.indexOf("ENGINE-BODY"));
    }

    @Test
    void compose_nullAddonSections_leavesPromptUntouched() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        // Builder.addonSections(null) is a no-op → key not in ctx.
        Map<String, Object> ctx = PromptContextBuilder.create()
                .addonSections(null).build();

        String out = SystemPrompts.compose(
                process, "Plain engine.", renderer, ctx);

        assertThat(out).isEqualTo("Plain engine.");
    }

    @Test
    void compose_blankAddonSections_skipsAutoAppend() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride(null);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .addonSections("").build();

        String out = SystemPrompts.compose(
                process, "Plain engine.", renderer, ctx);

        assertThat(out).isEqualTo("Plain engine.");
    }

    @Test
    void compose_addonSections_readableInsideRecipeOverride() {
        // The recipe author can reference {{ addonSections }} too —
        // useful when the recipe overrides the engine default but still
        // wants the addon material embedded.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("Recipe: {{ addonSections }}");
        process.setPromptMode(PromptMode.OVERWRITE);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .addonSections("ADDON").build();

        String out = SystemPrompts.compose(
                process, "engine default", renderer, ctx);

        assertThat(out).isEqualTo("Recipe: ADDON");
    }

    @Test
    void compose_overwriteMode_addonSectionsDropWhenOverrideDoesNotReferenceThem() {
        // Explicit OVERWRITE-without-reference is the recipe author's
        // way of opting out of engine-scoped addon material.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("Recipe rules.");
        process.setPromptMode(PromptMode.OVERWRITE);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .addonSections("ADDON").build();

        String out = SystemPrompts.compose(
                process, "engine default", renderer, ctx);

        assertThat(out).isEqualTo("Recipe rules.");
    }

    @Test
    void compose_appendMode_engineDefaultGetsAddonsThenRecipeOverrideJoined() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setPromptOverride("Recipe rules.");
        process.setPromptMode(PromptMode.APPEND);
        Map<String, Object> ctx = PromptContextBuilder.create()
                .addonSections("ADDON").build();

        String out = SystemPrompts.compose(
                process, "Engine.", renderer, ctx);

        int engineIdx = out.indexOf("Engine.");
        int addonIdx = out.indexOf("ADDON");
        int sepIdx = out.indexOf("--- recipe extension ---");
        int recipeIdx = out.indexOf("Recipe rules.");
        assertThat(addonIdx).isGreaterThan(engineIdx);
        assertThat(sepIdx).isGreaterThan(addonIdx);
        assertThat(recipeIdx).isGreaterThan(sepIdx);
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
