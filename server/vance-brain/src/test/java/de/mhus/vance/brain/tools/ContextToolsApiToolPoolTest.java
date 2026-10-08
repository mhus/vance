package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tool pool in {@link ContextToolsApi#classify}: tools released by label
 * join a restricted base as deferred. The base stays the engine core (the
 * manifest); the pool is reachable, never a schema flood, and every gate that
 * applies to base tools applies to pool tools too.
 */
class ContextToolsApiToolPoolTest {

    private static final Set<String> POOL = Set.of(ToolLabels.WORKER);

    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolInvocationContext ctx =
            new ToolInvocationContext("tenant", "project", "session", "process", "user");
    private final List<ToolDispatcher.Resolved> universe = new ArrayList<>();

    @BeforeEach
    void setUp() {
        register("doc_read", /*deferred*/ false, Set.of(ToolLabels.WORKER), Set.of());
        register("execute_javascript", /*deferred*/ false, Set.of(ToolLabels.WORKER, "executive"), Set.of());
        register("setting_set", /*deferred*/ false, Set.of(ToolLabels.OPERATOR), Set.of());
        register("health_write", /*deferred*/ false, Set.of(ToolLabels.WORKER), Set.of("tool-health-writer"));
        when(dispatcher.resolveAll(any())).thenReturn(universe);
    }

    @Test
    void releasedTool_joinsTheCoreDeferred() {
        ContextToolsApi.Classification c = classify(Set.of("doc_read"), RecipeResolver.ToolFilter.EMPTY, POOL);

        assertThat(c.allowed()).contains("doc_read", "execute_javascript");
        assertThat(c.primary()).contains("doc_read").doesNotContain("execute_javascript");
        // Its own flag says primary — the pool still keeps it out of the
        // manifest: the core is the manifest, the pool is discovery.
        assertThat(c.deferred()).contains("execute_javascript");
    }

    @Test
    void operatorTool_staysOutside() {
        ContextToolsApi.Classification c = classify(Set.of("doc_read"), RecipeResolver.ToolFilter.EMPTY, POOL);

        assertThat(c.allowed()).doesNotContain("setting_set");
    }

    @Test
    void roleGatedTool_staysOutside_evenWhenReleased() {
        ContextToolsApi.Classification c = classify(Set.of("doc_read"), RecipeResolver.ToolFilter.EMPTY, POOL);

        assertThat(c.allowed()).doesNotContain("health_write");
    }

    @Test
    void recipeRemove_appliesToPoolTools() {
        RecipeResolver.ToolFilter filter =
                new RecipeResolver.ToolFilter(List.of("execute_javascript"), List.of(), List.of());

        ContextToolsApi.Classification c = classify(Set.of("doc_read"), filter, POOL);

        assertThat(c.allowed()).doesNotContain("execute_javascript");
    }

    @Test
    void recipeAdd_promotesAPoolTool() {
        RecipeResolver.ToolFilter filter =
                new RecipeResolver.ToolFilter(List.of(), List.of("execute_javascript"), List.of());

        ContextToolsApi.Classification c = classify(Set.of("doc_read"), filter, POOL);

        assertThat(c.primary()).contains("execute_javascript");
        assertThat(c.deferred()).doesNotContain("execute_javascript");
    }

    @Test
    void noPoolLabels_keepsTheBaseClosed() {
        ContextToolsApi.Classification c = classify(Set.of("doc_read"), RecipeResolver.ToolFilter.EMPTY, Set.of());

        assertThat(c.allowed()).containsExactly("doc_read");
    }

    private ContextToolsApi.Classification classify(
            Set<String> base, RecipeResolver.ToolFilter filter, Set<String> poolLabels) {
        return ContextToolsApi.classify(
                dispatcher, ctx, base, filter, Set.of(), null, Set.of(), poolLabels, null, null);
    }

    private void register(String name, boolean deferred, Set<String> labels, Set<String> roles) {
        Tool t = new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "stub " + name;
            }

            @Override
            public boolean primary() {
                return true;
            }

            @Override
            public boolean deferred() {
                return deferred;
            }

            @Override
            public Set<String> labels() {
                return labels;
            }

            @Override
            public Set<String> requiresEngineRoles() {
                return roles;
            }

            @Override
            public Map<String, Object> paramsSchema() {
                return Map.of();
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext c) {
                return Map.of();
            }
        };
        ToolSource src = new ToolSource() {
            @Override
            public String sourceId() {
                return "stub";
            }

            @Override
            public List<Tool> tools(ToolInvocationContext c) {
                return List.of(t);
            }

            @Override
            public Optional<Tool> find(String n, ToolInvocationContext c) {
                return n.equals(name) ? Optional.of(t) : Optional.empty();
            }
        };
        ToolDispatcher.Resolved resolved = new ToolDispatcher.Resolved(t, src);
        universe.add(resolved);
        when(dispatcher.resolve(eq(name), any())).thenReturn(Optional.of(resolved));
    }
}
