package de.mhus.vance.brain.admin.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.admin.AdminActionDto;
import de.mhus.vance.api.admin.AdminActionRunResultDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Registry contract: every {@link AdminAction} bean is listed (sorted by
 * id for a stable surface), find() resolves, and a duplicate id fails at
 * construction — the one place that can see the collision before the run
 * path becomes ambiguous.
 */
class AdminActionRegistryTest {

    /** Minimal action stub — the registry must not care what an action does. */
    private static AdminAction action(String id, AdminActionScope scope) {
        return new AdminAction() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Map<String, String> title() {
                return Map.of("en", id);
            }

            @Override
            public Map<String, String> description() {
                return Map.of("en", id + " description");
            }

            @Override
            public AdminActionScope scope() {
                return scope;
            }

            @Override
            public AdminActionRunResultDto run(AdminActionContext context) {
                return AdminActionRunResultDto.builder().actionId(id).ok(true).build();
            }
        };
    }

    @Test
    void list_isSortedAndCarriesDescriptors() {
        var registry = new AdminActionRegistry(List.of(
                action("z-check", AdminActionScope.TENANT_AND_PROJECT),
                action("a-refresh", AdminActionScope.TENANT_ONLY)));

        List<AdminActionDto> listed = registry.list();

        assertThat(listed).extracting(AdminActionDto::getId).containsExactly("a-refresh", "z-check");
        assertThat(listed.get(0).getTitle()).containsEntry("en", "a-refresh");
        assertThat(listed.get(1).getScope()).isEqualTo("TENANT_AND_PROJECT");
    }

    @Test
    void find_resolvesById() {
        var registry = new AdminActionRegistry(List.of(action("model-check", AdminActionScope.TENANT_AND_PROJECT)));

        assertThat(registry.find("model-check")).isPresent();
        assertThat(registry.find("nope")).isEmpty();
    }

    @Test
    void duplicateId_failsAtConstruction() {
        assertThatThrownBy(() -> new AdminActionRegistry(List.of(
                        action("model-check", AdminActionScope.TENANT_ONLY),
                        action("model-check", AdminActionScope.TENANT_AND_PROJECT))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate AdminAction id 'model-check'");
    }
}
