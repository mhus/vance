package de.mhus.vance.brain.eddie;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.thinkengine.plan.PlanModeActionSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link EddieActionSchema} unions in the shared
 * Plan-Mode types from {@link PlanModeActionSchema}. The actual
 * dispatch behaviour is covered by {@code PlanModeServiceTest};
 * here we only assert that Eddie's schema accepts the four action
 * types so the LLM can emit them.
 */
class EddieActionSchemaPlanModeTest {

    @Test
    void supportedTypes_containsAllPlanModeActions() {
        assertThat(EddieActionSchema.SUPPORTED_TYPES)
                .contains(
                        PlanModeActionSchema.TYPE_START_PLAN,
                        PlanModeActionSchema.TYPE_PROPOSE_PLAN,
                        PlanModeActionSchema.TYPE_START_EXECUTION,
                        PlanModeActionSchema.TYPE_TODO_UPDATE);
    }

    @Test
    void supportedTypes_alsoKeepsEddieOwnActions() {
        // Sanity: the extension didn't drop anything Eddie originally had.
        assertThat(EddieActionSchema.SUPPORTED_TYPES)
                .contains(
                        EddieActionSchema.TYPE_ANSWER,
                        EddieActionSchema.TYPE_DELEGATE_PROJECT,
                        EddieActionSchema.TYPE_RELAY,
                        EddieActionSchema.TYPE_LEARN);
    }

    @Test
    void schemaEnum_includesPlanModeTypes() {
        Map<String, Object> root = EddieActionSchema.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) root.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> typeProp = (Map<String, Object>) properties.get("type");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) typeProp.get("enum");

        assertThat(enumValues)
                .contains(
                        PlanModeActionSchema.TYPE_START_PLAN,
                        PlanModeActionSchema.TYPE_PROPOSE_PLAN,
                        PlanModeActionSchema.TYPE_START_EXECUTION,
                        PlanModeActionSchema.TYPE_TODO_UPDATE);
    }

    @Test
    void schemaProperties_includePlanModeParamKeys() {
        // The PROPOSE_PLAN / TODO_UPDATE handlers in PlanModeService
        // read these keys off the action params. Without the schema
        // entries, the LLM might not know to emit them.
        Map<String, Object> root = EddieActionSchema.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) root.get("properties");

        assertThat(properties)
                .containsKeys(
                        PlanModeActionSchema.PARAM_PLAN,
                        PlanModeActionSchema.PARAM_SUMMARY,
                        PlanModeActionSchema.PARAM_TODOS,
                        PlanModeActionSchema.PARAM_UPDATES,
                        PlanModeActionSchema.PARAM_NOTES);
    }

    @Test
    void eddieOwnTypes_excludesPlanModeTypes() {
        // The split-set is useful for engines or callers that need to
        // know which actions are Eddie-only vs shared. Plan-Mode types
        // must NOT leak into EDDIE_OWN_TYPES.
        for (String planType : PlanModeActionSchema.ALL_TYPES) {
            assertThat(EddieActionSchema.EDDIE_OWN_TYPES)
                    .as("EDDIE_OWN_TYPES should not contain %s", planType)
                    .doesNotContain(planType);
        }
    }
}
