package de.mhus.vance.brain.servertool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the seam between {@link ServerToolService} and its delegates —
 * built-in shadowing, registry delegation, and write fan-out through
 * {@link DocumentService}. Cascade fan-out and pack semantics live in
 * {@link ServerToolRegistryTest}.
 */
class ServerToolServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";

    private ServerToolRegistry registry;
    private ServerToolLoader loader;
    private ToolFactoryRegistry factoryRegistry;
    private DocumentService documentService;
    private ServerToolService service;

    @BeforeEach
    void setUp() {
        registry = mock(ServerToolRegistry.class);
        loader = mock(ServerToolLoader.class);
        factoryRegistry = mock(ToolFactoryRegistry.class);
        documentService = mock(DocumentService.class);
        service = new ServerToolService(registry, loader, factoryRegistry, documentService);
        // Set built-ins via the same hook BuiltInToolSource uses.
        service.setBuiltInProvider(new TestBuiltInProvider(
                List.of(stub("builtin_alpha"), stub("builtin_beta"))));
    }

    // ─────── Built-in shadowing ───────

    @Test
    void lookup_returns_builtin_when_no_config_in_cascade() {
        when(registry.findConfig(eq(TENANT), eq(PROJECT), eq("builtin_alpha")))
                .thenReturn(Optional.empty());

        Optional<Tool> hit = service.lookup(TENANT, PROJECT, "builtin_alpha");

        assertThat(hit).map(Tool::name).contains("builtin_alpha");
    }

    @Test
    void lookup_disabled_config_shadows_builtin() {
        when(registry.findConfig(eq(TENANT), eq(PROJECT), eq("builtin_alpha")))
                .thenReturn(Optional.of(disabledConfig("builtin_alpha")));
        when(registry.lookup(eq(TENANT), eq(PROJECT), eq("builtin_alpha")))
                .thenReturn(Optional.empty());

        Optional<Tool> hit = service.lookup(TENANT, PROJECT, "builtin_alpha");

        assertThat(hit).isEmpty();
    }

    @Test
    void lookup_enabled_config_replaces_builtin() {
        when(registry.findConfig(eq(TENANT), eq(PROJECT), eq("builtin_alpha")))
                .thenReturn(Optional.of(enabledConfig("builtin_alpha")));
        when(registry.lookup(eq(TENANT), eq(PROJECT), eq("builtin_alpha"), any()))
                .thenReturn(Optional.of(stub("registry_replacement")));

        Optional<Tool> hit = service.lookup(TENANT, PROJECT, "builtin_alpha");

        assertThat(hit).map(Tool::name).contains("registry_replacement");
    }

    @Test
    void listAll_combines_builtins_and_registry_tools() {
        when(registry.listConfigs(eq(TENANT), eq(PROJECT))).thenReturn(List.of());
        when(registry.listAll(eq(TENANT), eq(PROJECT), any()))
                .thenReturn(List.of(stub("registry_one")));

        List<Tool> all = service.listAll(TENANT, PROJECT);

        assertThat(all).extracting(Tool::name)
                .containsExactly("builtin_alpha", "builtin_beta", "registry_one");
    }

    @Test
    void listAll_removes_builtins_shadowed_by_disabled_config() {
        when(registry.listConfigs(eq(TENANT), eq(PROJECT)))
                .thenReturn(List.of(disabledConfig("builtin_alpha")));
        when(registry.listAll(eq(TENANT), eq(PROJECT), any()))
                .thenReturn(List.of());

        List<Tool> all = service.listAll(TENANT, PROJECT);

        assertThat(all).extracting(Tool::name).containsExactly("builtin_beta");
    }

    // ─────── Write fan-out ───────

    @Test
    void create_persists_via_documentService() {
        // Refresh of the registry happens via the
        // DocumentChangedEvent → ServerToolDocumentListener chain (see
        // ServerToolDocumentListenerTest), not from inside ServerToolService
        // any more. The unit test here only asserts the write fan-out.
        when(factoryRegistry.find(eq("doc_lookup")))
                .thenReturn(Optional.of(new NoopFactory("doc_lookup")));
        when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq("_vance/server-tools/new_tool.yaml")))
                .thenReturn(Optional.empty());
        when(loader.validateYaml(eq("new_tool"), any())).thenReturn(enabledConfig("new_tool"));
        when(documentService.upsertText(
                eq(TENANT), eq(PROJECT), eq("_vance/server-tools/new_tool.yaml"),
                any(), any(), any(), any(), any()))
                .thenReturn(new DocumentDocument());
        when(registry.findConfig(eq(TENANT), eq(PROJECT), eq("new_tool")))
                .thenReturn(Optional.of(enabledConfig("new_tool")));

        ServerToolConfig stored = service.create(TENANT, PROJECT, enabledConfig("new_tool"));

        assertThat(stored.name()).isEqualTo("new_tool");
        verify(documentService).upsertText(
                eq(TENANT), eq(PROJECT), eq("_vance/server-tools/new_tool.yaml"),
                any(), any(), any(), any(), any());
        verify(registry, never()).refreshOne(any(), any(), any());
    }

    @Test
    void create_rejects_duplicate_name() {
        when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq("_vance/server-tools/existing.yaml")))
                .thenReturn(Optional.of(new DocumentDocument()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.create(TENANT, PROJECT, enabledConfig("existing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        verify(documentService, never()).upsertText(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_rejects_unknown_type() {
        ServerToolConfig bad = new ServerToolConfig(
                "x", "no_such_type", "desc",
                new LinkedHashMap<>(), new ArrayList<>(),
                true, false, new LinkedHashSet<>(), false, "",
                ServerToolConfig.Source.PROJECT, null, null, "");
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(factoryRegistry.find(eq("no_such_type"))).thenReturn(Optional.empty());
        when(factoryRegistry.list()).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.create(TENANT, PROJECT, bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown tool type");
    }

    @Test
    void delete_removes_document_and_lets_event_chain_refresh() {
        // Refresh of the registry is driven by the DocumentChangedEvent
        // that documentService.delete publishes — see
        // ServerToolDocumentListenerTest. Here we only assert that the
        // service hands off the delete and does not double-refresh.
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-123");
        when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq("_vance/server-tools/old.yaml")))
                .thenReturn(Optional.of(doc));

        service.delete(TENANT, PROJECT, "old");

        verify(documentService).delete(eq("doc-123"), any(de.mhus.vance.shared.permission.WriteActor.class));
        verify(registry, never()).refreshOne(any(), any(), any());
    }

    @Test
    void delete_absent_is_noop() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());

        service.delete(TENANT, PROJECT, "missing");

        verify(documentService, never()).delete(any(), any(de.mhus.vance.shared.permission.WriteActor.class));
        verify(registry, never()).refreshOne(any(), any(), any());
    }

    // ─────── Helpers ───────

    private static ServerToolConfig enabledConfig(String name) {
        return new ServerToolConfig(
                name, "doc_lookup", "test " + name,
                new LinkedHashMap<>(Map.of("path", "_vance/manuals/" + name + ".md")),
                new ArrayList<>(),
                /*enabled*/ true,
                /*primary*/ false,
                new LinkedHashSet<>(),
                /*defaultDeferred*/ false,
                /*promptHint*/ "",
                ServerToolConfig.Source.PROJECT,
                /*documentId*/ "doc-" + name,
                /*createdBy*/ null,
                /*yaml*/ "");
    }

    private static ServerToolConfig disabledConfig(String name) {
        ServerToolConfig base = enabledConfig(name);
        return new ServerToolConfig(
                base.name(), base.type(), base.description(),
                base.parameters(), base.labels(),
                /*enabled*/ false,
                base.primary(), base.disabledSubTools(), base.defaultDeferred(), base.promptHint(),
                base.source(), base.documentId(), base.createdBy(), base.yaml());
    }

    private static Tool stub(String name) {
        return new StubTool(name);
    }

    private record TestBuiltInProvider(List<Tool> tools)
            implements ServerToolService.BuiltInProvider {

        @Override public List<Tool> list() { return tools; }

        @Override public Optional<Tool> find(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst();
        }
    }

    private record NoopFactory(String typeId) implements ToolFactory {
        @Override public String typeId() { return typeId; }
        @Override public Map<String, Object> parametersSchema() { return Map.of(); }
        @Override public Collection<Tool> create(ServerToolDocument document) { return List.of(); }
    }

    private record StubTool(String name) implements Tool {
        @Override public String name() { return name; }
        @Override public String description() { return "stub " + name; }
        @Override public boolean primary() { return false; }
        @Override public Map<String, Object> paramsSchema() { return Map.of(); }
        @Override public Map<String, Object> invoke(
                Map<String, Object> p, ToolInvocationContext c) {
            return Map.of();
        }
    }
}
