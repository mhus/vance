package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.tools.ToolServiceDto;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowToolsControllerTest {

    private final ToolSource builtin = source("BUILTIN");
    private final ToolSource pack = source("SERVER");

    @Test
    void tools_are_grouped_by_service_and_a_tool_without_one_by_its_source() {
        List<ToolServiceDto> groups = WorkflowToolsController.group(List.of(
                resolved(tool("jira__search", "jira", Map.of("type", "object")), pack),
                resolved(tool("jira__create", "jira", null), pack),
                resolved(tool("doc_read", null, null), builtin),
                resolved(tool("slack__post", "slack", null), pack)));

        assertThat(groups).extracting(ToolServiceDto::getService).containsExactly("BUILTIN", "jira", "slack");
        assertThat(groups.get(1).getTools())
                .extracting(t -> t.getName())
                .containsExactly("jira__create", "jira__search");
    }

    @Test
    void a_tool_that_knows_its_result_lists_it_and_one_that_does_not_lists_none() {
        List<ToolServiceDto> groups = WorkflowToolsController.group(List.of(
                resolved(tool("jira__search", "jira", Map.of("type", "object")), pack),
                resolved(tool("jira__create", "jira", null), pack)));

        var tools = groups.get(0).getTools();
        assertThat(tools.get(0).getName()).isEqualTo("jira__create");
        assertThat(tools.get(0).getOutputSchema()).isNull();
        assertThat(tools.get(1).getOutputSchema()).containsEntry("type", "object");
        assertThat(tools.get(1).getService()).isEqualTo("jira");
    }

    @Test
    void a_tool_a_tool_task_refuses_is_left_out() {
        Tool restricted = tool("process_create", null, null, Set.of("arthur"));

        assertThat(WorkflowToolsController.group(List.of(resolved(restricted, builtin))))
                .isEmpty();
    }

    private static ToolSource source(String id) {
        ToolSource s = mock(ToolSource.class);
        when(s.sourceId()).thenReturn(id);
        return s;
    }

    private static ToolDispatcher.Resolved resolved(Tool tool, ToolSource source) {
        return new ToolDispatcher.Resolved(tool, source);
    }

    private static Tool tool(String name, String service, Map<String, Object> output) {
        return tool(name, service, output, Set.of());
    }

    private static Tool tool(String name, String service, Map<String, Object> output, Set<String> roles) {
        return new Tool() {
            @Override
            public Set<String> requiresEngineRoles() {
                return roles;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public boolean primary() {
                return false;
            }

            @Override
            public Map<String, Object> paramsSchema() {
                return Map.of("type", "object");
            }

            @Override
            public Map<String, Object> outputSchema() {
                return output;
            }

            @Override
            public String service() {
                return service;
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext c) {
                return Map.of();
            }
        };
    }
}
