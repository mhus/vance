package de.mhus.vance.brain.benjy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Consistency sweep over the bundled Benjy recipes — same idea as
 * {@code BundledRecipeStructureTest}: a broken recipe must fail here,
 * at build time, not at the first spawn.
 *
 * <p>Checks that (a) every spawnable Benjy recipe parses into a valid
 * {@link BenjyFeatureConfig} (fail-fast contract §4d), and (b) every
 * LightLm profile they reference exists as a bundled recipe, is marked
 * {@code internal: true} and carries a prompt — a missing profile would
 * otherwise surface as a LightLlmException mid-run.
 */
class BenjyRecipeConsistencyTest {

    /** The spawnable outer recipes (benjy-batch included — it is a
     *  user-pickable worker, only its stage set is mechanical). The
     *  benjy-architect recipe is a Slart wrapper, not a Benjy config. */
    private static final List<String> SPAWNABLE_BENJY_RECIPES = List.of(
            "benjy.yaml", "benjy-coding.yaml",
            "benjy-research.yaml", "benjy-batch.yaml");

    @Test
    void bundledBenjyRecipes_parseIntoValidFeatureConfigs() {
        for (String recipe : SPAWNABLE_BENJY_RECIPES) {
            Map<String, Object> spec = parse(recipe);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) spec.get("params");
            assertThat(params).as("%s params", recipe).isNotNull();
            // throws with a caller-ready message on any misconfiguration
            BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(params, "test-process");
            assertThat(cfg.getInterpretRecipe()).as("%s interpret", recipe).isNotBlank();
            assertThat(cfg.getDoRecipe()).as("%s doRecipe", recipe).isIn("benjy-do-coding", "benjy-do-research");
        }
    }

    @Test
    void referencedLightLmProfiles_existInternalAndPrompted() {
        for (String recipe : SPAWNABLE_BENJY_RECIPES) {
            Map<String, Object> spec = parse(recipe);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) spec.get("params");
            BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(params, "test-process");
            List<String> profiles = new ArrayList<>();
            profiles.add(cfg.getInterpretRecipe());
            if (cfg.getRouteRecipe() != null) profiles.add(cfg.getRouteRecipe());
            if (cfg.getEvaluateRecipe() != null) profiles.add(cfg.getEvaluateRecipe());
            if (cfg.getReflectRecipe() != null) profiles.add(cfg.getReflectRecipe());
            if (cfg.getEscalationRecipe() != null) profiles.add(cfg.getEscalationRecipe());
            profiles.add(cfg.getDoRecipe());

            for (String profile : profiles) {
                Map<String, Object> target = parse(profile + ".yaml");
                assertThat(target)
                        .as("%s references %s, which must exist", recipe, profile)
                        .isNotNull();
                if (!profile.equals(cfg.getDoRecipe()) && !profile.equals(cfg.getEscalationRecipe())) {
                    assertThat(Boolean.TRUE.equals(target.get("internal")))
                            .as("%s (LightLm profile) must be internal:true", profile)
                            .isTrue();
                }
                assertThat(String.valueOf(target.get("promptPrefix")).trim())
                        .as("%s promptPrefix", profile)
                        .isNotEmpty();
                assertThat(target.get("engine")).as("%s engine", profile).isNotNull();
            }
        }
    }

    @Test
    void benjyDoers_targetFordAndDeferBackends() {
        for (String doer : List.of("benjy-do-coding.yaml", "benjy-do-research.yaml")) {
            Map<String, Object> spec = parse(doer);
            assertThat(spec.get("engine")).as("%s engine", doer).isEqualTo("ford");
            assertThat(Boolean.TRUE.equals(spec.get("listed")))
                    .as("%s: the doer is Benjy-driven, not user-spawnable", doer)
                    .isFalse();
            @SuppressWarnings("unchecked")
            List<String> defer = (List<String>) spec.get("allowedToolsDefer");
            assertThat(defer).as("%s deferred backends", doer).contains("work_exec_run", "client_exec_run");
        }
    }

    @Test
    void bundledBenjyRecipes_pinTheStructuralItemCap() {
        // Decision #23: the minimal rule (bounded first batch, rest via
        // reflect-gaps) is a number, not a prompt — every spawnable recipe
        // must pin maxInitialItems so the shipped default is explicit and
        // a tenant override has a visible baseline.
        for (String recipe : SPAWNABLE_BENJY_RECIPES) {
            Map<String, Object> spec = parse(recipe);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) spec.get("params");
            Object cap = params == null ? null : params.get("maxInitialItems");
            assertThat(cap).as("%s must pin params.maxInitialItems", recipe).isInstanceOf(Number.class);
            assertThat(((Number) cap).intValue())
                    .as("%s maxInitialItems must be >= 1", recipe)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    private static Map<String, Object> parse(String fileName) {
        try {
            Path path = new ClassPathResource("vance-defaults/_vance/recipes/" + fileName)
                    .getFile()
                    .toPath();
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            return spec;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
