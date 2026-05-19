package de.mhus.vance.brain.servertool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServerToolRegistry}: cascade-merged config
 * loading, pack-disable, sub-tool-disable, lazy materialisation,
 * refresh semantics.
 *
 * <p>{@code ServerToolLoader} and {@code ToolFactoryRegistry} are
 * mocked — the test exercises only the registry's caching/lookup logic.
 */
class ServerToolRegistryTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";

    private ServerToolLoader loader;
    private ToolFactoryRegistry factoryRegistry;
    private ServerToolRegistry registry;
    private RecordingPackFactory packFactory;

    @BeforeEach
    void setUp() {
        loader = mock(ServerToolLoader.class);
        factoryRegistry = mock(ToolFactoryRegistry.class);
        packFactory = new RecordingPackFactory();
        when(factoryRegistry.find(eq("rest_api"))).thenReturn(Optional.of(packFactory));
        when(factoryRegistry.find(eq("doc_lookup"))).thenReturn(Optional.of(new SingletonFactory()));
        registry = new ServerToolRegistry(loader, factoryRegistry);
    }

    // ─────── Bootstrap ───────

    @Test
    void bootstrap_loads_entries_into_scope() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(
                singletonConfig("doc_a"),
                singletonConfig("doc_b")));

        int loaded = registry.bootstrapProject(TENANT, PROJECT);

        assertThat(loaded).isEqualTo(2);
        assertThat(registry.listConfigs(TENANT, PROJECT))
                .extracting(ServerToolConfig::name)
                .containsExactly("doc_a", "doc_b");
    }

    @Test
    void bootstrap_is_idempotent_and_replaces_scope() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(singletonConfig("doc_a")));
        registry.bootstrapProject(TENANT, PROJECT);

        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(singletonConfig("doc_b")));
        registry.bootstrapProject(TENANT, PROJECT);

        assertThat(registry.listConfigs(TENANT, PROJECT))
                .extracting(ServerToolConfig::name)
                .containsExactly("doc_b");
    }

    // ─────── Lookup ───────

    @Test
    void lookup_returns_enabled_singleton_tool() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(singletonConfig("doc_a")));
        registry.bootstrapProject(TENANT, PROJECT);

        Optional<Tool> hit = registry.lookup(TENANT, PROJECT, "doc_a");

        assertThat(hit).isPresent();
        assertThat(hit.get().name()).isEqualTo("doc_a");
    }

    @Test
    void lookup_disabled_pack_returns_empty() {
        ServerToolConfig disabled = packConfig("jira", false, Set.of(), "create", "search");
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(disabled));
        registry.bootstrapProject(TENANT, PROJECT);

        assertThat(registry.lookup(TENANT, PROJECT, "jira")).isEmpty();
        assertThat(registry.lookup(TENANT, PROJECT, "jira__create")).isEmpty();
    }

    @Test
    void lookup_subtool_returns_correct_pack_member() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(
                packConfig("jira", true, Set.of(), "create", "search", "delete")));
        registry.bootstrapProject(TENANT, PROJECT);

        assertThat(registry.lookup(TENANT, PROJECT, "jira__search"))
                .map(Tool::name)
                .contains("jira__search");
    }

    @Test
    void lookup_disabled_subtool_returns_empty() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(
                packConfig("jira", true, Set.of("delete"), "create", "search", "delete")));
        registry.bootstrapProject(TENANT, PROJECT);

        assertThat(registry.lookup(TENANT, PROJECT, "jira__delete")).isEmpty();
        // Other sub-tools of the same pack still resolve.
        assertThat(registry.lookup(TENANT, PROJECT, "jira__create")).isPresent();
    }

    @Test
    void lookup_unknown_returns_empty() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(singletonConfig("doc_a")));
        registry.bootstrapProject(TENANT, PROJECT);

        assertThat(registry.lookup(TENANT, PROJECT, "missing")).isEmpty();
    }

    @Test
    void lookup_without_bootstrap_returns_empty() {
        assertThat(registry.lookup(TENANT, PROJECT, "anything")).isEmpty();
    }

    // ─────── listAll / findByLabel ───────

    @Test
    void listAll_filters_disabled_packs() {
        ServerToolConfig live = packConfig("jira", true, Set.of(), "create");
        ServerToolConfig dead = packConfig("github", false, Set.of(), "issue");
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(live, dead));
        registry.bootstrapProject(TENANT, PROJECT);

        assertThat(registry.listAll(TENANT, PROJECT))
                .extracting(Tool::name)
                .containsExactly("jira__create");
    }

    @Test
    void listAll_filters_disabled_subtools() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(
                packConfig("jira", true, Set.of("delete"), "create", "search", "delete")));
        registry.bootstrapProject(TENANT, PROJECT);

        assertThat(registry.listAll(TENANT, PROJECT))
                .extracting(Tool::name)
                .containsExactlyInAnyOrder("jira__create", "jira__search");
    }

    @Test
    void findByLabel_filters_to_matching_tools() {
        ServerToolConfig labelled = withLabels(packConfig("jira", true, Set.of(), "create"), "ticketing");
        ServerToolConfig other = singletonConfig("doc_a");
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(labelled, other));
        registry.bootstrapProject(TENANT, PROJECT);

        // Pack sub-tools inherit labels via the test factory.
        assertThat(registry.findByLabel(TENANT, PROJECT, "ticketing"))
                .extracting(Tool::name)
                .containsExactly("jira__create");
    }

    // ─────── findConfig ───────

    @Test
    void findConfig_returns_disabled_entry() {
        ServerToolConfig disabled = packConfig("jira", false, Set.of(), "create");
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(disabled));
        registry.bootstrapProject(TENANT, PROJECT);

        Optional<ServerToolConfig> cfg = registry.findConfig(TENANT, PROJECT, "jira");

        assertThat(cfg).isPresent();
        assertThat(cfg.get().enabled()).isFalse();
    }

    @Test
    void findConfig_subtool_resolves_to_pack() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(
                packConfig("jira", true, Set.of(), "create")));
        registry.bootstrapProject(TENANT, PROJECT);

        assertThat(registry.findConfig(TENANT, PROJECT, "jira__create"))
                .map(ServerToolConfig::name)
                .contains("jira");
    }

    // ─────── Refresh ───────

    @Test
    void refreshOne_replaces_existing_entry() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(
                packConfig("jira", true, Set.of(), "create")));
        registry.bootstrapProject(TENANT, PROJECT);
        when(loader.load(eq(TENANT), eq(PROJECT), eq("jira"))).thenReturn(Optional.of(
                packConfig("jira", true, Set.of(), "create", "search")));

        boolean ok = registry.refreshOne(TENANT, PROJECT, "jira");

        assertThat(ok).isTrue();
        assertThat(registry.listAll(TENANT, PROJECT))
                .extracting(Tool::name)
                .containsExactlyInAnyOrder("jira__create", "jira__search");
    }

    @Test
    void refreshOne_removes_entry_when_deleted() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(singletonConfig("doc_a")));
        registry.bootstrapProject(TENANT, PROJECT);
        when(loader.load(eq(TENANT), eq(PROJECT), eq("doc_a"))).thenReturn(Optional.empty());

        boolean ok = registry.refreshOne(TENANT, PROJECT, "doc_a");

        assertThat(ok).isFalse();
        assertThat(registry.listConfigs(TENANT, PROJECT)).isEmpty();
    }

    @Test
    void refreshOne_parse_error_drops_entry() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(singletonConfig("doc_a")));
        registry.bootstrapProject(TENANT, PROJECT);
        when(loader.load(eq(TENANT), eq(PROJECT), eq("doc_a"))).thenThrow(
                new ServerToolLoader.ServerToolParseException("bad yaml", new RuntimeException()));

        boolean ok = registry.refreshOne(TENANT, PROJECT, "doc_a");

        assertThat(ok).isFalse();
        assertThat(registry.listConfigs(TENANT, PROJECT)).isEmpty();
    }

    @Test
    void refreshOne_without_scope_returns_false() {
        assertThat(registry.refreshOne(TENANT, PROJECT, "anything")).isFalse();
    }

    // ─────── Unload ───────

    @Test
    void unloadProject_drops_the_scope() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(singletonConfig("doc_a")));
        registry.bootstrapProject(TENANT, PROJECT);

        registry.unloadProject(TENANT, PROJECT);

        assertThat(registry.listConfigs(TENANT, PROJECT)).isEmpty();
        assertThat(registry.lookup(TENANT, PROJECT, "doc_a")).isEmpty();
    }

    // ─────── Materialisation ───────

    @Test
    void materialisation_is_lazy_and_cached() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(
                packConfig("jira", true, Set.of(), "create", "search")));
        registry.bootstrapProject(TENANT, PROJECT);

        // Pack factory should not be invoked just because we bootstrap.
        assertThat(packFactory.callCount.get()).isZero();

        registry.lookup(TENANT, PROJECT, "jira__create");
        registry.lookup(TENANT, PROJECT, "jira__search");
        registry.listAll(TENANT, PROJECT);

        // Three accesses, but only one factory.create() call — lazy + cached.
        assertThat(packFactory.callCount.get()).isEqualTo(1);
    }

    @Test
    void refresh_rebuilds_materialisation_on_next_access() {
        when(loader.listAll(eq(TENANT), eq(PROJECT))).thenReturn(List.of(
                packConfig("jira", true, Set.of(), "create")));
        registry.bootstrapProject(TENANT, PROJECT);
        registry.lookup(TENANT, PROJECT, "jira__create"); // builds once

        when(loader.load(eq(TENANT), eq(PROJECT), eq("jira"))).thenReturn(Optional.of(
                packConfig("jira", true, Set.of(), "create", "search")));
        registry.refreshOne(TENANT, PROJECT, "jira");

        int callsBefore = packFactory.callCount.get();
        registry.lookup(TENANT, PROJECT, "jira__search");
        // The new entry is materialised once for the second lookup.
        assertThat(packFactory.callCount.get()).isEqualTo(callsBefore + 1);
    }

    // ─────── Helpers ───────

    private static ServerToolConfig singletonConfig(String name) {
        return new ServerToolConfig(
                name, "doc_lookup", "test " + name,
                new LinkedHashMap<>(Map.of("path", "manuals/" + name + ".md")),
                new ArrayList<>(),
                /*enabled*/ true,
                /*primary*/ false,
                new LinkedHashSet<>(),
                /*defaultDeferred*/ false,
                ServerToolConfig.Source.PROJECT,
                /*documentId*/ null,
                /*createdBy*/ null,
                /*yaml*/ "");
    }

    private static ServerToolConfig packConfig(
            String name, boolean enabled, Set<String> disabledSubs, String... subNames) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subNames", List.of(subNames));
        return new ServerToolConfig(
                name, "rest_api", "test pack " + name,
                params,
                new ArrayList<>(),
                enabled,
                /*primary*/ false,
                new LinkedHashSet<>(disabledSubs),
                /*defaultDeferred*/ false,
                ServerToolConfig.Source.PROJECT,
                /*documentId*/ "doc-" + name,
                /*createdBy*/ null,
                /*yaml*/ "");
    }

    private static ServerToolConfig withLabels(ServerToolConfig base, String... labels) {
        return new ServerToolConfig(
                base.name(), base.type(), base.description(),
                base.parameters(),
                new ArrayList<>(List.of(labels)),
                base.enabled(),
                base.primary(),
                base.disabledSubTools(),
                base.defaultDeferred(),
                base.source(),
                base.documentId(),
                base.createdBy(),
                base.yaml());
    }

    /** Test factory: fans out the doc into N sub-tools per the {@code subNames} param. */
    private static final class RecordingPackFactory implements ToolFactory {
        final AtomicInteger callCount = new AtomicInteger();

        @Override public String typeId() { return "rest_api"; }
        @Override public Map<String, Object> parametersSchema() { return Map.of(); }

        @Override
        @SuppressWarnings("unchecked")
        public Collection<Tool> create(ServerToolDocument document) {
            callCount.incrementAndGet();
            List<String> subNames = (List<String>) document.getParameters().get("subNames");
            Set<String> packLabels = document.getLabels() == null
                    ? Set.of() : new LinkedHashSet<>(document.getLabels());
            List<Tool> out = new ArrayList<>(subNames.size());
            for (String sub : subNames) {
                String fullName = document.getName() + ToolFactory.PACK_SEPARATOR + sub;
                out.add(new StubTool(fullName, packLabels));
            }
            return out;
        }
    }

    /** Singleton factory: returns the doc as one tool with no labels. */
    private static final class SingletonFactory implements ToolFactory {
        @Override public String typeId() { return "doc_lookup"; }
        @Override public Map<String, Object> parametersSchema() { return Map.of(); }
        @Override
        public Collection<Tool> create(ServerToolDocument document) {
            return List.of(new StubTool(document.getName(), Set.of()));
        }
    }

    private static final class StubTool implements Tool {
        private final String name;
        private final Set<String> labels;
        StubTool(String name, Set<String> labels) { this.name = name; this.labels = labels; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub " + name; }
        @Override public boolean primary() { return false; }
        @Override public Set<String> labels() { return labels; }
        @Override public Map<String, Object> paramsSchema() { return Map.of(); }
        @Override public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext c) {
            return Map.of();
        }
    }
}
