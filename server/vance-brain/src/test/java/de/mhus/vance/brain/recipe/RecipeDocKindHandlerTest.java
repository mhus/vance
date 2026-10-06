package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-recipe} document kind. The loader
 * logs WARN and returns empty for a broken recipe — it is silently missing —
 * so every finding here is an ERROR: these are the checks whose failure
 * makes a recipe unspawnable. The deep validation (profiles, modes, guards)
 * stays with {@code RecipeLoader}, the one place that parses recipes for
 * every caller.
 */
class RecipeDocKindHandlerTest {

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override
        public boolean exists(String path) {
            return false;
        }

        @Override
        public @Nullable String kindOf(String path) {
            return null;
        }

        @Override
        public @Nullable Map<String, Object> readYaml(String path) {
            return null;
        }
    };

    private final PromptTemplateRenderer renderer = mock(PromptTemplateRenderer.class);

    private RecipeDocKindHandler handler(String... engines) {
        List<ThinkEngine> list = new ArrayList<>();
        for (String name : engines) {
            ThinkEngine engine = mock(ThinkEngine.class);
            when(engine.name()).thenReturn(name);
            list.add(engine);
        }
        return new RecipeDocKindHandler(renderer, list);
    }

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", NO_REFS);
    }

    @Test
    void detectsPath_claimsTheRecipeTree() {
        assertThat(handler("arthur").detectsPath("_vance/recipes/analyze.yaml")).isTrue();
        // Slart's working trees are recipe-shaped documents under the same
        // prefix — claimed with it.
        assertThat(handler("arthur").detectsPath("_vance/recipes/_user/draft.yaml"))
                .isTrue();
    }

    @Test
    void detectsPath_neverClaimsOutsideTheTree() {
        assertThat(handler("arthur").detectsPath("_vance/recipes-old/x.yaml")).isFalse();
        assertThat(handler("arthur").detectsPath("_vance/config/research/serper.yaml"))
                .isFalse();
        assertThat(handler("arthur").detectsPath("notes/recipes.md")).isFalse();
        assertThat(handler("arthur").detectsPath("_vance/recipes")).isFalse();
    }

    @Test
    void getName_isVanceRecipe() {
        assertThat(handler("arthur").getName()).isEqualTo("vance-recipe");
    }

    @Test
    void validate_minimalBody_hasNoFindings() {
        String yaml = """
                title: Probe
                description: A minimal recipe.
                engine: arthur
                """;

        assertThat(handler("arthur", "ford").validate(yaml, ctx("_vance/recipes/probe.yaml")))
                .isEmpty();
    }

    @Test
    void validate_brokenYaml_isOneParseError() {
        var findings = handler("arthur").validate("engine: [\n", ctx("broken.yaml"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("recipe-parse");
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
    }

    @Test
    void validate_emptyDocument_isAnError() {
        var findings = handler("arthur").validate("", ctx("empty.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("recipe-empty"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_missingDescriptionAndEngine_areErrors() {
        var findings = handler("arthur").validate("title: No fields\n", ctx("bare.yaml"));

        assertThat(findings).extracting(Finding::code).contains("recipe-description", "recipe-engine");
        assertThat(findings).allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_unknownEngine_isAnErrorListingWhatThisDeploymentServes() {
        var findings = handler("arthur", "ford").validate("description: x\nengine: scrumble\n", ctx("unknown.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("recipe-engine-unknown"))
                .allSatisfy(f -> {
                    assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
                    assertThat(f.message()).contains("ford");
                });
    }

    @Test
    void validate_paramsThatIsNotAMap_isAnError() {
        var findings = handler("arthur").validate("description: x\nengine: arthur\nparams: [a]\n", ctx("params.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("recipe-params"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_promptPrefixThatDoesNotCompile_isAnError() {
        org.mockito.Mockito.doThrow(new PromptTemplateException("unbalanced {% if %}"))
                .when(renderer)
                .compile(anyString());

        var findings = handler("arthur")
                .validate("description: x\nengine: arthur\npromptPrefix: \"{% if %}\"\n", ctx("prompt.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("recipe-prompt-prefix"))
                .allSatisfy(f -> {
                    assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
                    assertThat(f.message()).contains("promptPrefix");
                });
    }
}
