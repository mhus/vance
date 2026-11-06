package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.tools.ToolServiceDto;
import de.mhus.vance.brain.servicecatalog.ServiceCatalogEntry;
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

    @Test
    void a_group_with_a_catalog_entry_gets_its_description_login_state_and_the_highlights_that_exist() {
        List<ToolServiceDto> groups = WorkflowToolsController.group(List.of(
                resolved(tool("jira_rest__createIssue", "jira_rest", Map.of("type", "object")), pack),
                resolved(tool("jira_rest__getIssue", "jira_rest", Map.of("type", "object")), pack),
                resolved(tool("slack_rest__chat_postMessage", "slack_rest", Map.of("type", "object")), pack)));
        var jira = new ServiceCatalogEntry(
                "jira",
                Map.of("en", "Jira", "de", "Jira DE"),
                Map.of("en", "Tickets"),
                "ticket",
                List.of("jira"),
                "atlassian",
                "oauth-tools",
                List.of(
                        new ServiceCatalogEntry.Highlight("createIssue", Map.of("en", "Create"), Map.of("a", 1)),
                        new ServiceCatalogEntry.Highlight("noSuchOperation", Map.of("en", "Gone"), null)),
                List.of(new ServiceCatalogEntry.Trigger(
                        "created",
                        Map.of("en", "Issue created"),
                        ServiceCatalogEntry.TriggerMode.WEBHOOK,
                        null,
                        null,
                        null,
                        new ServiceCatalogEntry.Registration("createWebhook", Map.of(), null),
                        new ServiceCatalogEntry.Registration("deleteWebhook", Map.of(), null),
                        null,
                        List.of())));

        WorkflowToolsController.decorate(groups, List.of(jira), "de", provider -> "atlassian".equals(provider));

        assertThat(groups.stream()
                        .filter(g -> g.getService().equals("jira_rest"))
                        .findFirst()
                        .orElseThrow()
                        .getCatalog()
                        .getTriggers())
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getName()).isEqualTo("created");
                    assertThat(t.getMode()).isEqualTo("webhook");
                    assertThat(t.isSubscribes()).isTrue();
                });

        var jiraGroup = groups.stream()
                .filter(g -> g.getService().equals("jira_rest"))
                .findFirst()
                .orElseThrow();
        assertThat(jiraGroup.getCatalog().getTitle()).isEqualTo("Jira DE");
        assertThat(jiraGroup.getCatalog().getLoginProvider()).isEqualTo("atlassian");
        assertThat(jiraGroup.getCatalog().getConnected()).isTrue();
        // The highlight the pack does not offer is dropped; the one it does is named by its full tool name.
        assertThat(jiraGroup.getCatalog().getHighlights())
                .singleElement()
                .satisfies(h -> assertThat(h.getTool()).isEqualTo("jira_rest__createIssue"));
        // A group without an entry is left as it is.
        var slackGroup = groups.stream()
                .filter(g -> g.getService().equals("slack_rest"))
                .findFirst()
                .orElseThrow();
        assertThat(slackGroup.getCatalog()).isNull();
    }

    @Test
    void whether_the_user_is_connected_is_unknown_without_a_user_and_absent_without_a_login() {
        List<ToolServiceDto> groups = WorkflowToolsController.group(List.of(
                resolved(tool("a__x", "a", Map.of("type", "object")), pack),
                resolved(tool("b__x", "b", Map.of("type", "object")), pack)));
        var needsLogin = new ServiceCatalogEntry(
                "a", Map.of("en", "A"), Map.of(), null, List.of("a"), "p", null, List.of(), List.of());
        var needsNone = new ServiceCatalogEntry(
                "b", Map.of("en", "B"), Map.of(), null, List.of("b"), null, null, List.of(), List.of());

        WorkflowToolsController.decorate(groups, List.of(needsLogin, needsNone), "en", provider -> null);

        assertThat(groups.get(0).getCatalog().getConnected()).isNull();
        assertThat(groups.get(1).getCatalog().getLoginProvider()).isNull();
        assertThat(groups.get(1).getCatalog().getConnected()).isNull();
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
