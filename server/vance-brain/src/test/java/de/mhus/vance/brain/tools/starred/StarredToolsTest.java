package de.mhus.vance.brain.tools.starred;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Contract of the {@code starred_*} tool family. The invariants worth a test are
 * the ones a future edit could quietly break: the family stays deferred (tool
 * surface budget), the tools refuse to guess a user, and no tool accepts
 * {@code kind} / {@code type} as a parameter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StarredToolsTest {

    private static final String TENANT = "acme";
    private static final String USER = "mhu";

    @Mock private StarredService starredService;
    @Mock private SecurityContextFactory contextFactory;

    private StarredToolSupport support;
    private StarredListTool listTool;
    private StarredAddTool addTool;
    private StarredRemoveTool removeTool;
    private StarredReconcileTool reconcileTool;

    @BeforeEach
    void setUp() {
        support = new StarredToolSupport(contextFactory);
        listTool = new StarredListTool(starredService, support);
        addTool = new StarredAddTool(starredService, support);
        removeTool = new StarredRemoveTool(starredService, support);
        reconcileTool = new StarredReconcileTool(starredService, support);
        when(contextFactory.forToolSubject(any(), any())).thenReturn(SecurityContext.SYSTEM);
    }

    private List<Tool> allTools() {
        return List.of(listTool, addTool, removeTool, reconcileTool);
    }

    @Test
    void wholeFamilyIsDeferred() {
        // Four permanent schemas would be badly spent tool-surface budget for a
        // side feature — the manual and discovery bring them in on demand.
        assertThat(allTools()).allSatisfy(t -> assertThat(t.deferred()).isTrue());
    }

    @Test
    void namesShareOneFamilyPrefix() {
        // The budget layer groups by the prefix before the first '_', so a
        // stray name would land the tool in someone else's family.
        assertThat(allTools()).extracting(Tool::name)
                .containsExactly("starred_list", "starred_add",
                        "starred_remove", "starred_reconcile");
    }

    @Test
    void noToolAcceptsKindOrType_asAWritableField() {
        // kind/type are read from the live document. A model asked for a type
        // supplies a plausible one, and a wrong type breaks a "send to" with
        // nothing in the UI saying so. `starred_list` may *filter* on them.
        for (Tool t : List.of(addTool, removeTool, reconcileTool)) {
            assertThat(properties(t)).doesNotContainKeys("kind", "type");
        }
        assertThat(properties(listTool)).containsKeys("kind", "type");
    }

    @Test
    void headlessInvocation_failsInsteadOfGuessingAUser() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                TENANT, "work", null, null, /* userId */ null);

        assertThatThrownBy(() -> listTool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("belongs to a person");
    }

    @Test
    void list_typeFilter_goesThroughTheTypeLookup() {
        when(starredService.listByType(TENANT, USER, "links")).thenReturn(List.of(
                StarredItem.builder().project("work").path("links/_app.yaml")
                        .kind("application").type("links").title("Links").build()));

        Map<String, Object> out = listTool.invoke(Map.of("type", "links"), ctx());

        assertThat(out.get("total")).isEqualTo(1);
        assertThat(out).doesNotContainKey("hint");
    }

    @Test
    void list_emptyResult_saysWhoSetsAStar() {
        when(starredService.listResolvable(TENANT, USER)).thenReturn(List.of());

        Map<String, Object> out = listTool.invoke(Map.of(), ctx());

        assertThat(out.get("total")).isEqualTo(0);
        assertThat(String.valueOf(out.get("hint"))).contains("set by the person");
    }

    @Test
    void add_passesTheTriStateFlagsThrough() {
        when(starredService.star(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(StarredItem.builder()
                        .project("work").path("a.md").kind("text").build());

        addTool.invoke(Map.of(
                "project", "work",
                "path", "a.md",
                "hidden", true), ctx());

        // description/highlight omitted → null → "leave as is".
        verify(starredService).star(eq(TENANT), eq(USER), eq("work"), eq("a.md"),
                eq(null), eq(null), eq(null), eq(Boolean.TRUE), any());
    }

    @Test
    void add_missingTargetBecomesAToolError() {
        when(starredService.star(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new StarredService.StarredException("No document 'gone.md'"));

        assertThatThrownBy(() -> addTool.invoke(
                Map.of("project", "work", "path", "gone.md"), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("gone.md");
    }

    @Test
    void remove_reportsWhetherAnythingChanged() {
        when(starredService.unstar(eq(TENANT), eq(USER), eq("work"), eq("a.md"), any()))
                .thenReturn(false);

        Map<String, Object> out = removeTool.invoke(
                Map.of("project", "work", "path", "a.md"), ctx());

        assertThat(out.get("changed")).isEqualTo(false);
        assertThat(String.valueOf(out.get("message"))).contains("Not starred");
    }

    @Test
    void reconcile_countsBrokenEntriesAndTellsTheAgentToOfferRemoval() {
        when(starredService.reconcile(eq(TENANT), eq(USER), any()))
                .thenReturn(new StarredService.ReconcileResult(List.of(
                        new StarredService.ReconcileEntry("work", "a.md",
                                StarredService.ReconcileOutcome.OK, "up to date"),
                        new StarredService.ReconcileEntry("work", "gone.md",
                                StarredService.ReconcileOutcome.MISSING, "no document"),
                        new StarredService.ReconcileEntry("secret", "b.md",
                                StarredService.ReconcileOutcome.FORBIDDEN, "no access")),
                        false));

        Map<String, Object> out = reconcileTool.invoke(Map.of(), ctx());

        assertThat(out.get("checked")).isEqualTo(3);
        assertThat(out.get("broken")).isEqualTo(2L);
        assertThat(String.valueOf(out.get("hint"))).contains("starred_remove");
    }

    @Test
    void reconcile_allHealthy_hasNoHint() {
        when(starredService.reconcile(eq(TENANT), eq(USER), any()))
                .thenReturn(new StarredService.ReconcileResult(List.of(
                        new StarredService.ReconcileEntry("work", "a.md",
                                StarredService.ReconcileOutcome.OK, "up to date")),
                        false));

        assertThat(reconcileTool.invoke(Map.of(), ctx())).doesNotContainKey("hint");
    }

    @Test
    void row_omitsDefaultsAndNeverReportsEnabled() {
        // Everything the tools hand out is registered by definition, so an
        // `enabled: true` on every row would only cost tokens.
        Map<String, Object> row = StarredToolSupport.row(StarredItem.builder()
                .project("work").path("a.md").kind("text").build());

        assertThat(row).containsOnlyKeys("project", "path", "kind");
    }

    @Test
    void row_reportsHiddenAndHighlightWhenSet() {
        Map<String, Object> row = StarredToolSupport.row(StarredItem.builder()
                .project("work").path("a.md").kind("text")
                .hidden(true).highlight(true).build());

        assertThat(row).containsEntry("hidden", true).containsEntry("highlight", true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Tool tool) {
        Object props = tool.paramsSchema().get("properties");
        return props instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext(TENANT, "work", null, null, USER);
    }
}
