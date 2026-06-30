package de.mhus.vance.addon.brain.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the deterministic, Mongo-free parts of
 * {@link WorkspaceFormService}: target-path resolution (where the data
 * file gets written) and the edit-config field-schema parsing.
 */
class WorkspaceFormServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void resolveRelative_relativeTarget_isResolvedAgainstConfigFolder() {
        assertThat(WorkspaceFormService.resolveRelative(
                "studium-ws26/inputs-edit-config.yaml", "inputs.yaml"))
                .isEqualTo("studium-ws26/inputs.yaml");
    }

    @Test
    void resolveRelative_leadingSlashTarget_isProjectAbsolute() {
        assertThat(WorkspaceFormService.resolveRelative(
                "studium-ws26/inputs-edit-config.yaml", "/shared/data.yaml"))
                .isEqualTo("shared/data.yaml");
    }

    @Test
    void resolveRelative_configAtProjectRoot_keepsBareTarget() {
        assertThat(WorkspaceFormService.resolveRelative("config.yaml", "inputs.yaml"))
                .isEqualTo("inputs.yaml");
    }

    @Test
    void parseFields_validSchema_returnsTypedFields() {
        Map<String, Object> config = Map.of(
                "target", "inputs.yaml",
                "form", Map.of("fields", List.of(
                        Map.of("name", "title", "type", "string", "required", true),
                        Map.of("name", "tags", "type", "multi_select"))));

        List<FormFieldDto> fields =
                WorkspaceFormService.parseFields(objectMapper, config, "x/edit-config.yaml");

        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).getName()).isEqualTo("title");
        assertThat(fields.get(0).getType()).isEqualTo("string");
        assertThat(fields.get(0).isRequired()).isTrue();
        assertThat(fields.get(1).getName()).isEqualTo("tags");
        assertThat(fields.get(1).getType()).isEqualTo("multi_select");
    }

    @Test
    void parseFields_noFormBlock_returnsEmptyList() {
        Map<String, Object> config = Map.of("target", "inputs.yaml");

        List<FormFieldDto> fields =
                WorkspaceFormService.parseFields(objectMapper, config, "x/edit-config.yaml");

        assertThat(fields).isEmpty();
    }
}
