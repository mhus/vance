package de.mhus.vance.brain.tools.builtins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@code tool_description}. The behaviour that matters:
 * describing several tools in <em>one</em> call (a REST pack has 15–20
 * sub-tools — per-name round-trips are what make a model give up), and
 * activating exactly the tools that sit in the turn's deferred bucket.
 */
class ToolDescriptionToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj", "sess-1", "p-1", "alice");

    private ToolDispatcher dispatcher;
    private ThinkProcessService thinkProcessService;
    private ToolDescriptionTool tool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dispatcher = mock(ToolDispatcher.class);
        thinkProcessService = mock(ThinkProcessService.class);
        when(thinkProcessService.activateDeferredTool(any(), any())).thenReturn(true);

        ObjectProvider<ToolDispatcher> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(dispatcher);
        tool = new ToolDescriptionTool(provider, thinkProcessService);
    }

    /** Registers a resolvable tool under its own name. */
    private Tool register(String name, boolean deferred) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.description()).thenReturn(name + " does things");
        when(t.primary()).thenReturn(!deferred);
        when(t.deferred()).thenReturn(deferred);
        when(t.searchHint()).thenReturn("hint for " + name);
        when(t.paramsSchema()).thenReturn(Map.of("type", "object"));
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(dispatcher.resolve(eq(name), any()))
                .thenReturn(Optional.of(new ToolDispatcher.Resolved(t, src)));
        return t;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> described(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("tools");
    }

    @Test
    void describesEveryRequestedName_inOneCall() {
        register("jira_rest__searchIssues", true);
        register("jira_rest__createIssue", true);

        Map<String, Object> out = tool.invoke(
                Map.of("names", List.of("jira_rest__searchIssues", "jira_rest__createIssue")),
                CTX);

        assertThat(described(out)).extracting(m -> m.get("name"))
                .containsExactly("jira_rest__searchIssues", "jira_rest__createIssue");
        assertThat(described(out)).allSatisfy(m ->
                assertThat(m).containsKeys("description", "paramsSchema", "deferred", "activated"));
        assertThat(out).doesNotContainKey("unknown");
    }

    @Test
    void unknownNames_areReportedWithoutFailingTheKnownOnes() {
        register("doc_read", false);
        when(dispatcher.resolve(eq("gmail_send"), any())).thenReturn(Optional.empty());

        Map<String, Object> out =
                tool.invoke(Map.of("names", List.of("doc_read", "gmail_send")), CTX);

        assertThat(described(out)).extracting(m -> m.get("name")).containsExactly("doc_read");
        assertThat(out.get("unknown")).isEqualTo(List.of("gmail_send"));
    }

    @Test
    void allNamesUnknown_throwsRatherThanReturningAnEmptySuccess() {
        when(dispatcher.resolve(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tool.invoke(Map.of("names", List.of("nope", "nada")), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nope, nada");
    }

    @Test
    void missingNames_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'names' is required");
    }

    @Test
    void batchIsCapped_andTheOverhangIsReported() {
        List<String> requested = new ArrayList<>();
        for (int i = 0; i < ToolDescriptionTool.MAX_BATCH + 3; i++) {
            String name = "pack__op" + i;
            register(name, true);
            requested.add(name);
        }

        Map<String, Object> out = tool.invoke(Map.of("names", requested), CTX);

        assertThat(described(out)).hasSize(ToolDescriptionTool.MAX_BATCH);
        assertThat((List<?>) out.get("skipped")).hasSize(3);
        assertThat(out.get("skippedReason")).asString().contains("ask again for the rest");
    }

    @Test
    void deferredTool_isActivatedForTheSession() {
        register("history_search", true);

        Map<String, Object> out = tool.invoke(Map.of("names", List.of("history_search")), CTX);

        verify(thinkProcessService).activateDeferredTool("p-1", "history_search");
        assertThat(described(out).getFirst()).containsEntry("activated", true);
        assertThat(described(out).getFirst()).containsEntry("deferred", true);
    }

    @Test
    void primaryTool_isNotActivated() {
        register("doc_read", false);

        Map<String, Object> out = tool.invoke(Map.of("names", List.of("doc_read")), CTX);

        verify(thinkProcessService, never()).activateDeferredTool(any(), any());
        assertThat(described(out).getFirst()).containsEntry("activated", false);
    }

    @Test
    void boundSurfaceDecidesTheBucket_notTheToolsStaticDefault() {
        // Tool defaults to deferred, but the recipe-driven classification
        // for this turn put it in the primary bucket → nothing to activate.
        register("history_search", true);
        ContextToolsApi surface =
                new ContextToolsApi(dispatcher, CTX, Set.of("history_search", "tool_description"));

        Map<String, Object> out =
                tool.invoke(Map.of("names", List.of("history_search")), CTX, surface);

        verify(thinkProcessService, never()).activateDeferredTool(any(), any());
        assertThat(described(out).getFirst()).containsEntry("deferred", false);
    }

    @Test
    void namesOutsideTheEngineAllowSet_areNotDescribed() {
        // Same sight-line as tool_list: a caged engine must not be able to
        // enumerate schemas it cannot invoke, and the two discovery tools
        // must agree about what exists.
        register("doc_read", false);
        register("kit_install", true);
        ContextToolsApi caged =
                new ContextToolsApi(dispatcher, CTX, Set.of("doc_read", "tool_description"));

        Map<String, Object> out =
                tool.invoke(Map.of("names", List.of("doc_read", "kit_install")), CTX, caged);

        assertThat(described(out)).extracting(m -> m.get("name")).containsExactly("doc_read");
        assertThat(out.get("unknown")).isEqualTo(List.of("kit_install"));
        verify(thinkProcessService, never()).activateDeferredTool(any(), any());
    }

    @Test
    void theDiscoveryPairItselfStaysDescribable_evenOnARawAllowSet() {
        // Surfaces built without classify (raw allow-set, sub-tool paths)
        // don't carry the floor in `allowed` — "what parameters do you
        // take?" must still work on the pair itself.
        register("tool_description", false);
        ContextToolsApi caged = new ContextToolsApi(dispatcher, CTX, Set.of("doc_read"));

        Map<String, Object> out =
                tool.invoke(Map.of("names", List.of("tool_description")), CTX, caged);

        assertThat(described(out)).extracting(m -> m.get("name"))
                .containsExactly("tool_description");
    }

    @Test
    void namesAcceptsBareStringAndCommaSeparatedForm() {
        assertThat(ToolDescriptionTool.parseNames("doc_read")).containsExactly("doc_read");
        assertThat(ToolDescriptionTool.parseNames(" a , b ,, a "))
                .containsExactly("a", "b");
        assertThat(ToolDescriptionTool.parseNames(List.of("x", "", " y ", "x")))
                .containsExactly("x", "y");
        assertThat(ToolDescriptionTool.parseNames(null)).isEmpty();
    }

    @Test
    void withoutBus_theStaticDeferredFlagDrivesActivation() {
        register("history_search", true);

        tool.invoke(Map.of("names", List.of("history_search")), CTX, ToolBus.NOOP);

        verify(thinkProcessService).activateDeferredTool("p-1", "history_search");
    }
}
