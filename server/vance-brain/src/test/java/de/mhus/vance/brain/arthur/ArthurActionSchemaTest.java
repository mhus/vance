package de.mhus.vance.brain.arthur;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Validates the static action-vocabulary contracts of
 * {@link ArthurActionSchema}: per-mode allow-set composition and the
 * JSON schema's required fields. No Spring, no Arthur instance — pure
 * data-shape tests.
 *
 * <p>See {@code readme/arthur-plan-mode.md} §15.1
 * "ArthurActionSchemaValidationTest".
 */
class ArthurActionSchemaTest {

    // ─── typesForMode: per-mode allow-set composition ──────────────────

    @Test
    void typesForMode_normal_includesAllOriginalActions_plusStartPlan() {
        Set<String> normal = ArthurActionSchema.typesForMode(ProcessMode.NORMAL);
        assertThat(normal).contains(
                ArthurActionSchema.TYPE_ANSWER,
                ArthurActionSchema.TYPE_ASK_USER,
                ArthurActionSchema.TYPE_DELEGATE,
                ArthurActionSchema.TYPE_RELAY,
                ArthurActionSchema.TYPE_WAIT,
                ArthurActionSchema.TYPE_REJECT,
                ArthurActionSchema.TYPE_START_PLAN);
    }

    @Test
    void typesForMode_normal_excludesPlanModeInternalActions() {
        Set<String> normal = ArthurActionSchema.typesForMode(ProcessMode.NORMAL);
        assertThat(normal).doesNotContain(
                ArthurActionSchema.TYPE_PROPOSE_PLAN,
                ArthurActionSchema.TYPE_START_EXECUTION);
    }

    @Test
    void typesForMode_exploring_isReadOnlyTriad() {
        Set<String> exploring = ArthurActionSchema.typesForMode(ProcessMode.EXPLORING);
        assertThat(exploring).containsExactlyInAnyOrder(
                ArthurActionSchema.TYPE_ANSWER,
                ArthurActionSchema.TYPE_PROPOSE_PLAN,
                ArthurActionSchema.TYPE_START_PLAN);
    }

    @Test
    void typesForMode_exploring_blocksWriteAndDelegateActions() {
        Set<String> exploring = ArthurActionSchema.typesForMode(ProcessMode.EXPLORING);
        assertThat(exploring).doesNotContain(
                ArthurActionSchema.TYPE_DELEGATE,
                ArthurActionSchema.TYPE_RELAY,
                ArthurActionSchema.TYPE_START_EXECUTION,
                ArthurActionSchema.TYPE_TODO_UPDATE,
                ArthurActionSchema.TYPE_REJECT,
                ArthurActionSchema.TYPE_WAIT,
                ArthurActionSchema.TYPE_ASK_USER);
    }

    @Test
    void typesForMode_planning_allowsApprovalEditAndClarification() {
        Set<String> planning = ArthurActionSchema.typesForMode(ProcessMode.PLANNING);
        assertThat(planning).contains(
                ArthurActionSchema.TYPE_START_EXECUTION,
                ArthurActionSchema.TYPE_PROPOSE_PLAN,
                ArthurActionSchema.TYPE_ANSWER,
                ArthurActionSchema.TYPE_START_PLAN);
    }

    @Test
    void typesForMode_planning_blocksDelegationAndExecutionActions() {
        Set<String> planning = ArthurActionSchema.typesForMode(ProcessMode.PLANNING);
        assertThat(planning).doesNotContain(
                ArthurActionSchema.TYPE_DELEGATE,
                ArthurActionSchema.TYPE_RELAY,
                ArthurActionSchema.TYPE_TODO_UPDATE,
                ArthurActionSchema.TYPE_WAIT,
                ArthurActionSchema.TYPE_REJECT,
                ArthurActionSchema.TYPE_ASK_USER);
    }

    @Test
    void typesForMode_executing_includesTodoUpdateAndFullWorkVocabulary() {
        Set<String> executing = ArthurActionSchema.typesForMode(ProcessMode.EXECUTING);
        assertThat(executing).contains(
                ArthurActionSchema.TYPE_ANSWER,
                ArthurActionSchema.TYPE_DELEGATE,
                ArthurActionSchema.TYPE_RELAY,
                ArthurActionSchema.TYPE_WAIT,
                ArthurActionSchema.TYPE_REJECT,
                ArthurActionSchema.TYPE_TODO_UPDATE,
                ArthurActionSchema.TYPE_START_PLAN,
                ArthurActionSchema.TYPE_ASK_USER);
    }

    @Test
    void typesForMode_executing_blocksPlanModeInternalActions() {
        Set<String> executing = ArthurActionSchema.typesForMode(ProcessMode.EXECUTING);
        assertThat(executing).doesNotContain(
                ArthurActionSchema.TYPE_PROPOSE_PLAN,
                ArthurActionSchema.TYPE_START_EXECUTION);
    }

    // ─── Schema shape ─────────────────────────────────────────────

    @Test
    void schema_declaresAllSupportedTypesInEnum() {
        Map<String, Object> root = ArthurActionSchema.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) root.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> typeProp = (Map<String, Object>) properties.get("type");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) typeProp.get("enum");
        assertThat(enumValues).containsAll(ArthurActionSchema.SUPPORTED_TYPES);
    }

    @Test
    void schema_requiresTypeAndReason() {
        Map<String, Object> root = ArthurActionSchema.schema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) root.get("required");
        assertThat(required).containsExactlyInAnyOrder("type", "reason");
    }

    @Test
    void schema_includesPlanModeProperties() {
        Map<String, Object> root = ArthurActionSchema.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) root.get("properties");
        assertThat(properties).containsKeys(
                ArthurActionSchema.PARAM_GOAL,
                ArthurActionSchema.PARAM_PLAN,
                ArthurActionSchema.PARAM_SUMMARY,
                ArthurActionSchema.PARAM_TODOS,
                ArthurActionSchema.PARAM_NOTES,
                ArthurActionSchema.PARAM_UPDATES);
    }

    @Test
    void schema_todosArrayDeclaresIdAndContentRequired() {
        Map<String, Object> root = ArthurActionSchema.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) root.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> todosProp = (Map<String, Object>) properties.get(ArthurActionSchema.PARAM_TODOS);
        @SuppressWarnings("unchecked")
        Map<String, Object> itemSchema = (Map<String, Object>) todosProp.get("items");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) itemSchema.get("required");
        assertThat(required).containsExactlyInAnyOrder("id", "content");
    }

    @Test
    void schema_updatesArrayDeclaresStatusEnum() {
        Map<String, Object> root = ArthurActionSchema.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) root.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> updatesProp = (Map<String, Object>) properties.get(ArthurActionSchema.PARAM_UPDATES);
        @SuppressWarnings("unchecked")
        Map<String, Object> itemSchema = (Map<String, Object>) updatesProp.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProps = (Map<String, Object>) itemSchema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> statusProp = (Map<String, Object>) itemProps.get("status");
        @SuppressWarnings("unchecked")
        List<String> statusEnum = (List<String>) statusProp.get("enum");
        assertThat(statusEnum).containsExactlyInAnyOrder(
                "PENDING", "IN_PROGRESS", "COMPLETED");
    }
}
