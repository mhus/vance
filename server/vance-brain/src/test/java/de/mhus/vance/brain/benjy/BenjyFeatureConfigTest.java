package de.mhus.vance.brain.benjy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fail-fast parsing of the recipe feature config (§4d limit 3): a broken
 * recipe must surface at the spawn turn with a caller-ready message,
 * not mid-run. Also pins the chain templates (§4c): which verification
 * stages apply per task type, with disabled stages dropping out.
 */
class BenjyFeatureConfigTest {

    private static final String PID = "p-1";

    private static Map<String, Object> validParams() {
        // LinkedHashMaps on both levels: several tests mutate the
        // features map (remove/put) — Map.of would throw on them.
        Map<String, Object> features = new java.util.LinkedHashMap<>();
        features.put("interpret", Map.of("recipe", "benjy-interpret"));
        features.put("route", Map.of("recipe", "benjy-route"));
        features.put("evaluate", Map.of("recipe", "benjy-evaluate"));
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("doRecipe", "benjy-do-coding");
        params.put("features", features);
        return params;
    }

    @Test
    void parsesMinimalConfig_featuresOffAreNull() {
        BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(validParams(), PID);
        assertThat(cfg.getInterpretRecipe()).isEqualTo("benjy-interpret");
        assertThat(cfg.getRouteRecipe()).isEqualTo("benjy-route");
        assertThat(cfg.getEvaluateRecipe()).isEqualTo("benjy-evaluate");
        assertThat(cfg.getCheckCommand()).isNull();
        assertThat(cfg.getReflectRecipe()).isNull();
        assertThat(cfg.getEscalationRecipe()).isNull();
        assertThat(cfg.getDoRecipe()).isEqualTo("benjy-do-coding");
        // no taskTypes given → all four active
        assertThat(cfg.getTaskTypes()).containsExactlyInAnyOrderElementsOf(BenjyFeatureConfig.TASK_TYPES);
    }

    @Test
    void missingInterpret_failsFast() {
        Map<String, Object> params = validParams();
        ((Map<String, Object>) params.get("features")).remove("interpret");
        assertThatThrownBy(() -> BenjyFeatureConfig.fromParams(params, PID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("features.interpret is required");
    }

    @Test
    void missingDoRecipe_failsFast() {
        Map<String, Object> params = validParams();
        params.remove("doRecipe");
        assertThatThrownBy(() -> BenjyFeatureConfig.fromParams(params, PID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.doRecipe is required");
    }

    @Test
    void unknownFeature_failsFast() {
        Map<String, Object> params = validParams();
        ((Map<String, Object>) params.get("features")).put("teleport", Map.of("recipe", "x"));
        assertThatThrownBy(() -> BenjyFeatureConfig.fromParams(params, PID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.features.teleport")
                .hasMessageContaining("not a known feature");
    }

    @Test
    void unknownTaskType_failsFast() {
        Map<String, Object> params = validParams();
        params.put("taskTypes", List.of("coding", "woodworking"));
        assertThatThrownBy(() -> BenjyFeatureConfig.fromParams(params, PID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown 'woodworking'");
    }

    @Test
    void explicitFeatureFalse_isOff() {
        Map<String, Object> params = validParams();
        ((Map<String, Object>) params.get("features")).put("route", false);
        BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(params, PID);
        assertThat(cfg.getRouteRecipe()).isNull();
    }

    @Test
    void criteriaSources_acceptRefMapsAndPlainStrings() {
        Map<String, Object> params = validParams();
        params.put("criteriaSources", List.of(Map.of("ref", "req/checklist.yaml"), "req/other.yaml"));
        BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(params, PID);
        assertThat(cfg.getCriteriaSources()).containsExactly("req/checklist.yaml", "req/other.yaml");
    }

    // ── Chain templates (§4c) ──

    @Test
    void chainFor_coding_includesCheckAndEvaluate() {
        Map<String, Object> params = validParams();
        ((Map<String, Object>) params.get("features")).put("check", Map.of("command", "python3 -m pytest -q"));
        BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(params, PID);
        assertThat(cfg.chainFor(BenjyFeatureConfig.TASK_TYPE_CODING))
                .containsExactly(
                        BenjyTaskTypes.DO, BenjyTaskTypes.CHECK, BenjyTaskTypes.EVALUATE, BenjyTaskTypes.CLOSE_ITEM);
    }

    @Test
    void chainFor_coding_withoutCheckCommand_dropsCheck() {
        BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(validParams(), PID);
        assertThat(cfg.chainFor(BenjyFeatureConfig.TASK_TYPE_CODING))
                .containsExactly(BenjyTaskTypes.DO, BenjyTaskTypes.EVALUATE, BenjyTaskTypes.CLOSE_ITEM);
    }

    @Test
    void chainFor_info_isDoAndCloseOnly() {
        BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(validParams(), PID);
        assertThat(cfg.chainFor(BenjyFeatureConfig.TASK_TYPE_INFO))
                .containsExactly(BenjyTaskTypes.DO, BenjyTaskTypes.CLOSE_ITEM);
    }

    @Test
    void chainFor_planningWithoutEvaluate_degradesToDoAndClose() {
        Map<String, Object> params = validParams();
        ((Map<String, Object>) params.get("features")).remove("evaluate");
        BenjyFeatureConfig cfg = BenjyFeatureConfig.fromParams(params, PID);
        assertThat(cfg.chainFor(BenjyFeatureConfig.TASK_TYPE_PLANNING))
                .containsExactly(BenjyTaskTypes.DO, BenjyTaskTypes.CLOSE_ITEM);
    }
}
