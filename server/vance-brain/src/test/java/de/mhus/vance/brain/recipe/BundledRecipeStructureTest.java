package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Structural sweep over every bundled recipe.
 *
 * <p>Exists because of a failure that produced no error anywhere:
 * {@code coding.yaml} and {@code trillian-worker-0.yaml} both carried
 * their whole {@code promptPrefix} indented under {@code params}. That
 * map is open by design — the loader stores unknown keys as engine
 * parameters — so YAML parsed, the recipe loaded, and the prompt simply
 * never existed. The Trillian worker ran without its termination
 * contract for as long as the recipe had been there, and the symptom
 * read as a model ignoring instructions.
 *
 * <p>A per-recipe test would not have caught it; only looking at all of
 * them does.
 */
class BundledRecipeStructureTest {

    /** Fields the loader reads from the top level. */
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "engine", "description", "title", "promptPrefix", "promptMode",
            "dataRelayCorrection", "allowedToolsAdd", "allowedToolsRemove",
            "allowedToolsKeep", "allowedToolsDefer", "allowedToolsDropFirst",
            "defaultActiveSkills", "allowedSkills", "triggers", "tags",
            "modes", "profiles", "guard", "internal", "listed", "locked");

    @Test
    void noBundledRecipeHidesATopLevelFieldInsideParams() {
        List<String> offenders = new ArrayList<>();
        for (Path recipe : bundledRecipes()) {
            Map<String, Object> spec = parse(recipe);
            if (!(spec.get("params") instanceof Map<?, ?> params)) {
                continue;
            }
            for (Object key : params.keySet()) {
                if (TOP_LEVEL_FIELDS.contains(String.valueOf(key))) {
                    offenders.add(recipe.getFileName() + " → params." + key);
                }
            }
        }
        assertThat(offenders)
                .as("a top-level recipe field nested under 'params' is accepted silently "
                        + "and has no effect — move it out")
                .isEmpty();
    }

    @Test
    void everyRecipeDeclaresAnEngine() {
        for (Path recipe : bundledRecipes()) {
            assertThat(parse(recipe))
                    .as("recipe %s", recipe.getFileName())
                    .containsKey("engine");
        }
    }

    private static List<Path> bundledRecipes() {
        try {
            Path dir = new ClassPathResource("vance-defaults/_vance/recipes")
                    .getFile().toPath();
            try (var files = Files.list(dir)) {
                List<Path> yaml = files
                        .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .sorted()
                        .toList();
                assertThat(yaml).as("bundled recipes found").isNotEmpty();
                return yaml;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path recipe) {
        try {
            return (Map<String, Object>) new Yaml()
                    .load(Files.readString(recipe, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
