package de.mhus.vance.brain.tools;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.recipe.RecipeResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link ContextToolsApi#classify} — verifies the
 * primary/deferred bucket split that drives the per-turn tool manifest.
 *
 * <p>Apply order under test (§14.2):
 * Remove → Add (force-primary) → Defer (force-deferred). Tool's own
 * {@link de.mhus.vance.toolpack.Tool#deferred()} default is the
 * tie-breaker when no explicit add/defer applies.
 */
class ContextToolsApiClassifyTest {

    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "tenant", "project", "session", "process", "user");

    @Test
    void emptyBase_returnsEmptyClassification() {
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of(), RecipeResolver.ToolFilter.EMPTY, Set.of());

        assertThat(c.allowed()).isEmpty();
        assertThat(c.primary()).isEmpty();
        assertThat(c.deferred()).isEmpty();
        assertThat(c.activatedDeferred()).isEmpty();
    }

    @Test
    void noFilter_classifiesByToolDeferredFlag() {
        stubResolve("primary_tool", false);
        stubResolve("deferred_tool", true);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("primary_tool", "deferred_tool"),
                RecipeResolver.ToolFilter.EMPTY,
                Set.of());

        assertThat(c.primary()).containsExactly("primary_tool");
        assertThat(c.deferred()).containsExactly("deferred_tool");
    }

    @Test
    void filterAdd_promotesDeferredDefaultToPrimary() {
        stubResolve("kit_install", true); // defaults to deferred
        stubResolve("doc_read", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("kit_install"), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("kit_install", "doc_read"), filter, Set.of());

        assertThat(c.primary()).containsExactlyInAnyOrder("kit_install", "doc_read");
        assertThat(c.deferred()).isEmpty();
    }

    @Test
    void filterDefer_demotesPrimaryDefaultToDeferred() {
        stubResolve("doc_read", false);
        stubResolve("doc_list", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of(), List.of("doc_read"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("doc_read", "doc_list"), filter, Set.of());

        assertThat(c.primary()).containsExactly("doc_list");
        assertThat(c.deferred()).containsExactly("doc_read");
    }

    @Test
    void filterRemove_dropsTool_fromAllowedAndPrimary() {
        stubResolve("doc_edit", false);
        stubResolve("doc_read", false);

        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of("doc_edit"), List.of(), List.of());
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("doc_edit", "doc_read"), filter, Set.of());

        assertThat(c.allowed()).containsExactly("doc_read");
        assertThat(c.primary()).containsExactly("doc_read");
        assertThat(c.deferred()).isEmpty();
    }

    @Test
    void deferOverridesAdd_whenSameToolInBoth() {
        stubResolve("foo", false);
        // §14.2: apply order is Remove → Add → Defer; defer wins at tail.
        RecipeResolver.ToolFilter filter = new RecipeResolver.ToolFilter(
                List.of(), List.of("foo"), List.of("foo"));
        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx, Set.of("foo"), filter, Set.of());

        assertThat(c.deferred()).containsExactly("foo");
        assertThat(c.primary()).isEmpty();
    }

    @Test
    void activatedDeferred_isFiltered_toDeferredBucketIntersection() {
        stubResolve("kit_install", true);
        stubResolve("doc_read", false);

        ContextToolsApi.Classification c = ContextToolsApi.classify(
                dispatcher, ctx,
                Set.of("kit_install", "doc_read"),
                RecipeResolver.ToolFilter.EMPTY,
                // Activations referencing tools not in the deferred bucket
                // are silently dropped — a stale activation for an
                // already-promoted tool shouldn't pollute the manifest.
                Set.of("kit_install", "doc_read", "stale_unknown"));

        assertThat(c.activatedDeferred()).containsExactly("kit_install");
    }

    @Test
    void visibleTools_areSortedAlphabetically_forCacheStability() {
        stubResolve("zeta", false);
        stubResolve("alpha", false);
        stubResolve("mu", true);
        when(dispatcher.resolveAll(any())).thenReturn(List.of(
                resolved("zeta"), resolved("alpha"), resolved("mu")));

        ContextToolsApi api = new ContextToolsApi(
                dispatcher, ctx,
                /*allowed*/ Set.of("zeta", "alpha", "mu"),
                /*primary*/ Set.of("zeta", "alpha"),
                /*deferred*/ Set.of("mu"),
                /*activatedDeferred*/ Set.of("mu"),
                ToolInvocationListener.NOOP);

        List<de.mhus.vance.api.tools.ToolSpec> visible = api.listPrimary();
        assertThat(visible).extracting("name")
                .containsExactly("alpha", "mu", "zeta");
    }

    private void stubResolve(String name, boolean deferred) {
        when(dispatcher.resolve(eq(name), any())).thenReturn(Optional.of(resolved(name, deferred)));
    }

    private static ToolDispatcher.Resolved resolved(String name) {
        return resolved(name, false);
    }

    private static ToolDispatcher.Resolved resolved(String name, boolean deferred) {
        Tool t = new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public boolean primary() { return true; }
            @Override public boolean deferred() { return deferred; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext c) {
                return Map.of();
            }
        };
        ToolSource src = new ToolSource() {
            @Override public String sourceId() { return "stub"; }
            @Override public List<Tool> tools(ToolInvocationContext c) { return List.of(t); }
            @Override public Optional<Tool> find(String n, ToolInvocationContext c) {
                return n.equals(name) ? Optional.of(t) : Optional.empty();
            }
        };
        return new ToolDispatcher.Resolved(t, src);
    }
}
