package de.mhus.vance.brain.servertool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.shared.servertool.ServerToolRepository;
import java.time.Instant;
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
 * Unit tests for the multi-tool pack mechanics in
 * {@link ServerToolService}: pack-disable, per-sub-tool disable,
 * cascade-override semantics, and the {@code updatedAt}-keyed pack
 * cache. See {@code planning/server-tool-providers.md}.
 */
class ServerToolServicePackFanOutTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";

    private ServerToolRepository repository;
    private ToolFactoryRegistry factoryRegistry;
    private ServerToolService service;
    private RecordingPackFactory packFactory;

    @BeforeEach
    void setUp() {
        repository = mock(ServerToolRepository.class);
        factoryRegistry = mock(ToolFactoryRegistry.class);
        packFactory = new RecordingPackFactory();
        when(factoryRegistry.find(eq("rest_api"))).thenReturn(Optional.of(packFactory));
        when(factoryRegistry.find(eq("doc_lookup"))).thenReturn(Optional.of(new SingletonFactory()));
        when(factoryRegistry.list()).thenReturn(List.of(packFactory, new SingletonFactory()));
        service = new ServerToolService(repository, factoryRegistry);
    }

    // ─────── Fan-out + Disable ───────

    @Test
    void packDoc_fansOutToMultipleSubTools_inListAll() {
        stubProjectDocs(restApiPack("jira", "create", "search", "delete"));
        stubVanceDocs();

        List<Tool> all = service.listAll(TENANT, PROJECT);

        assertThat(all).extracting(Tool::name)
                .containsExactlyInAnyOrder(
                        "jira" + ToolFactory.PACK_SEPARATOR + "create",
                        "jira" + ToolFactory.PACK_SEPARATOR + "search",
                        "jira" + ToolFactory.PACK_SEPARATOR + "delete");
    }

    @Test
    void packDoc_disabled_removesAllSubTools() {
        ServerToolDocument disabled = restApiPack("jira", "create", "search");
        disabled.setEnabled(false);
        stubProjectDocs(disabled);
        stubVanceDocs();

        List<Tool> all = service.listAll(TENANT, PROJECT);

        assertThat(all).isEmpty();
    }

    @Test
    void disabledSubTools_removesOnlyMatchingSub() {
        ServerToolDocument doc = restApiPack("jira", "create", "search", "delete");
        doc.setDisabledSubTools(Set.of("delete"));
        stubProjectDocs(doc);
        stubVanceDocs();

        List<Tool> all = service.listAll(TENANT, PROJECT);

        assertThat(all).extracting(Tool::name)
                .containsExactlyInAnyOrder(
                        "jira" + ToolFactory.PACK_SEPARATOR + "create",
                        "jira" + ToolFactory.PACK_SEPARATOR + "search");
    }

    @Test
    void singletonPackFactory_emitsOneToolWithDocName() {
        stubProjectDocs(singletonDoc("doc_intro"));
        stubVanceDocs();

        List<Tool> all = service.listAll(TENANT, PROJECT);

        assertThat(all).extracting(Tool::name).containsExactly("doc_intro");
    }

    // ─────── Cascade-Override ───────

    @Test
    void projectPack_overridesVancePackEntirely() {
        stubVanceDocs(restApiPack("jira", "old1", "old2"));
        stubProjectDocs(restApiPack("jira", "new1", "new2", "new3"));

        List<Tool> all = service.listAll(TENANT, PROJECT);

        // Project doc replaces the _vance pack: only new* sub-tools remain.
        assertThat(all).extracting(Tool::name)
                .containsExactlyInAnyOrder(
                        "jira" + ToolFactory.PACK_SEPARATOR + "new1",
                        "jira" + ToolFactory.PACK_SEPARATOR + "new2",
                        "jira" + ToolFactory.PACK_SEPARATOR + "new3");
    }

    @Test
    void projectDisabledPack_hidesVancePackEvenWithoutOwnSubTools() {
        stubVanceDocs(restApiPack("jira", "create", "search"));
        ServerToolDocument shadow = restApiPack("jira", "irrelevant"); // would be ignored
        shadow.setEnabled(false);
        stubProjectDocs(shadow);

        List<Tool> all = service.listAll(TENANT, PROJECT);

        assertThat(all).isEmpty();
    }

    // ─────── Pack-Cache ───────

    @Test
    void packCache_avoidsRebuildWhenUpdatedAtUnchanged() {
        ServerToolDocument doc = restApiPack("jira", "create", "search");
        doc.setId("doc-id-1");
        doc.setUpdatedAt(Instant.parse("2026-05-07T10:00:00Z"));
        when(repository.findByTenantIdAndProjectId(eq(TENANT), eq(PROJECT)))
                .thenReturn(List.of(doc));
        stubVanceDocs();

        service.listAll(TENANT, PROJECT);
        service.listAll(TENANT, PROJECT);
        service.listAll(TENANT, PROJECT);

        // Three reads, factory.create() called only once because updatedAt
        // is identical across all three.
        assertThat(packFactory.callCount.get()).isEqualTo(1);
    }

    @Test
    void packCache_rebuildsWhenUpdatedAtChanges() {
        ServerToolDocument first = restApiPack("jira", "create");
        first.setId("doc-id-1");
        first.setUpdatedAt(Instant.parse("2026-05-07T10:00:00Z"));
        ServerToolDocument second = restApiPack("jira", "create", "search"); // edited
        second.setId("doc-id-1"); // same doc id
        second.setUpdatedAt(Instant.parse("2026-05-07T10:05:00Z"));
        when(repository.findByTenantIdAndProjectId(eq(TENANT), eq(PROJECT)))
                .thenReturn(List.of(first), List.of(second));
        stubVanceDocs();

        List<Tool> firstRead = service.listAll(TENANT, PROJECT);
        List<Tool> secondRead = service.listAll(TENANT, PROJECT);

        assertThat(firstRead).hasSize(1);
        assertThat(secondRead).hasSize(2);
        assertThat(packFactory.callCount.get()).isEqualTo(2);
    }

    // ─────── Lookup with <pack>__<sub> ───────

    @Test
    void lookupBySubName_resolvesViaPrefixSplit() {
        ServerToolDocument doc = restApiPack("jira", "create", "search");
        when(repository.findByTenantIdAndProjectIdAndName(
                eq(TENANT), eq(PROJECT), eq("jira"))).thenReturn(Optional.of(doc));

        Optional<Tool> result = service.lookup(
                TENANT, PROJECT, "jira" + ToolFactory.PACK_SEPARATOR + "search");

        assertThat(result).isPresent();
        assertThat(result.get().name())
                .isEqualTo("jira" + ToolFactory.PACK_SEPARATOR + "search");
    }

    @Test
    void lookupBySubName_returnsEmpty_whenSubInDisabledList() {
        ServerToolDocument doc = restApiPack("jira", "create", "search");
        doc.setDisabledSubTools(Set.of("search"));
        when(repository.findByTenantIdAndProjectIdAndName(
                eq(TENANT), eq(PROJECT), eq("jira"))).thenReturn(Optional.of(doc));

        Optional<Tool> result = service.lookup(
                TENANT, PROJECT, "jira" + ToolFactory.PACK_SEPARATOR + "search");

        assertThat(result).isEmpty();
    }

    @Test
    void lookupBySingletonName_resolvesExactMatch() {
        ServerToolDocument doc = singletonDoc("doc_intro");
        when(repository.findByTenantIdAndProjectIdAndName(
                eq(TENANT), eq(PROJECT), eq("doc_intro"))).thenReturn(Optional.of(doc));

        Optional<Tool> result = service.lookup(TENANT, PROJECT, "doc_intro");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("doc_intro");
    }

    // ─────── Helpers ───────

    private static ServerToolDocument restApiPack(String packName, String... subNames) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subNames", List.of(subNames));
        return ServerToolDocument.builder()
                .name(packName)
                .type("rest_api")
                .description("test pack " + packName)
                .parameters(params)
                .labels(new ArrayList<>())
                .enabled(true)
                .primary(false)
                .disabledSubTools(new LinkedHashSet<>())
                .defaultDeferred(false)
                .build();
    }

    private static ServerToolDocument singletonDoc(String name) {
        return ServerToolDocument.builder()
                .name(name)
                .type("doc_lookup")
                .description("test singleton " + name)
                .parameters(new LinkedHashMap<>(Map.of("path", "manuals/" + name + ".md")))
                .labels(new ArrayList<>())
                .enabled(true)
                .primary(false)
                .disabledSubTools(new LinkedHashSet<>())
                .defaultDeferred(false)
                .build();
    }

    private void stubVanceDocs(ServerToolDocument... docs) {
        when(repository.findByTenantIdAndProjectId(
                eq(TENANT), eq(HomeBootstrapService.VANCE_PROJECT_NAME)))
                .thenReturn(List.of(docs));
    }

    private void stubProjectDocs(ServerToolDocument... docs) {
        when(repository.findByTenantIdAndProjectId(eq(TENANT), eq(PROJECT)))
                .thenReturn(List.of(docs));
    }

    /** Test factory that fans out to N sub-tools per the doc's {@code subNames} param. */
    private static final class RecordingPackFactory implements ToolFactory {
        final AtomicInteger callCount = new AtomicInteger();

        @Override public String typeId() { return "rest_api"; }
        @Override public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        @SuppressWarnings("unchecked")
        public Collection<Tool> create(ServerToolDocument document) {
            callCount.incrementAndGet();
            List<String> subNames = (List<String>) document.getParameters().get("subNames");
            List<Tool> out = new ArrayList<>(subNames.size());
            for (String sub : subNames) {
                String fullName = document.getName() + ToolFactory.PACK_SEPARATOR + sub;
                out.add(new StubTool(fullName));
            }
            return out;
        }
    }

    /** Singleton factory that returns the doc as one tool. */
    private static final class SingletonFactory implements ToolFactory {
        @Override public String typeId() { return "doc_lookup"; }
        @Override public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }
        @Override
        public Collection<Tool> create(ServerToolDocument document) {
            return List.of(new StubTool(document.getName()));
        }
    }

    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub " + name; }
        @Override public boolean primary() { return false; }
        @Override public Map<String, Object> paramsSchema() { return Map.of(); }
        @Override public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext c) {
            return Map.of();
        }
    }
}
