package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import de.mhus.vance.brain.ai.decision.DecisionModelInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * The bundled Zem recipes, read with the same YAML parser the recipe
 * loader uses: the example profile must survive parsing and validation
 * against its pinned model AND against the minimal profile (nimble), and
 * the generic recipe must not carry a model that would override a
 * spawned profile's pin.
 */
class ZemBundledProfileTest {

    private static final DecisionModelInfo MINIMAL =
            new DecisionModelInfo("ollama", "nimble", 30, 64, 26, 10, false, null, 0.0, null, 1);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String name) throws IOException {
        try (InputStream in =
                new ClassPathResource("vance-defaults/_vance/recipes/" + name + ".yaml").getInputStream()) {
            return new Yaml().load(in);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void command_safety_profile_parses_pins_its_model_and_fits_the_minimal_profile() throws IOException {
        Map<String, Object> recipe = load("command-safety");
        assertThat(recipe).containsEntry("engine", "zem").containsEntry("internal", true);

        ZemProfile profile = ZemProfileParser.parse("command-safety", (Map<String, Object>) recipe.get("params"));

        assertThat(profile.model()).isEqualTo("openrouter:typesafe/jev-1.13"); // pinned, not an alias
        List<ZemQuestionDto> validated = ZemQuestionValidator.validate(profile.questions(), MINIMAL);
        assertThat(validated).singleElement().satisfies(q -> {
            assertThat(q.getName()).isEqualTo("unsafe");
            assertThat(q.getType()).isEqualTo(ZemQuestionType.NOUL);
            assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) q.getCriteria()).keySet()))
                    .containsExactlyInAnyOrder("true", "false");
        });
    }

    @Test
    void generic_recipe_carries_no_model() throws IOException {
        Map<String, Object> recipe = load("zem");

        assertThat(recipe).containsEntry("engine", "zem").doesNotContainKey("internal");
        assertThat(recipe.get("params")).isNull();
    }
}
