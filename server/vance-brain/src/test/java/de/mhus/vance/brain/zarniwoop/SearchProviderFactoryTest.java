package de.mhus.vance.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.brain.sourceconfig.SourceConfig;
import de.mhus.vance.brain.sourceconfig.SourceConfigLoader;
import de.mhus.vance.brain.sourceconfig.SourceConfigPaths;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchDomain;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProtocol;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchProviderFactoryTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "alpha";

    private static SearchProviderFactory factory(
            SourceConfigLoader loader, List<SearchProtocol> protocols) {
        return new SearchProviderFactory(loader, SecretResolver.PASSTHROUGH, protocols);
    }

    /** A configuration document as the loader would hand it over. */
    private static SourceConfig doc(String name, String protocol, Object... extraPairs) {
        Map<String, Object> extras = new LinkedHashMap<>();
        for (int i = 0; i < extraPairs.length; i += 2) {
            extras.put(String.valueOf(extraPairs[i]), extraPairs[i + 1]);
        }
        boolean enabled = !Boolean.FALSE.equals(extras.remove("enabled"));
        String baseUrl = (String) extras.remove("baseUrl");
        return new SourceConfig(
                name, SourceConfigPaths.pathFor(SourceConfigPaths.RESEARCH, name),
                protocol, baseUrl, (String) extras.remove("apiKey"), enabled, extras);
    }

    private static void given(SourceConfigLoader loader, SourceConfig... configs) {
        when(loader.load(eq(TENANT), eq(PROJECT), eq(SourceConfigPaths.RESEARCH)))
                .thenReturn(List.of(configs));
    }

    @Test
    void assemble_requires_project_scope() {
        SourceConfigLoader loader = mock(SourceConfigLoader.class);
        SearchProviderFactory factory = factory(loader, List.of());

        SearchScope tenantOnly = new SearchScope(TENANT, "", null, null);

        assertThatThrownBy(() -> factory.assemble(tenantOnly))
                .isInstanceOf(ZarniwoopException.class)
                .hasMessageContaining("project");
    }

    @Test
    void assemble_returns_empty_when_no_endpoints_configured() {
        SourceConfigLoader loader = mock(SourceConfigLoader.class);
        when(loader.load(eq(TENANT), eq(PROJECT), eq(SourceConfigPaths.RESEARCH)))
                .thenReturn(List.of());

        SearchProviderFactory factory = factory(loader, List.of());
        List<SearchProviderInstance> result = factory.assemble(SearchScope.of(TENANT, PROJECT));

        assertThat(result).isEmpty();
    }

    @Test
    void assemble_dispatches_to_protocol_for_each_endpoint() {
        SourceConfigLoader loader = mock(SourceConfigLoader.class);
        given(loader,
                doc("serper-main", "serper", "baseUrl", "https://google.serper.dev"),
                doc("wiki-de", "wikipedia", "baseUrl", "https://de.wikipedia.org/w/api.php"));

        RecordingProtocol serper = new RecordingProtocol("serper");
        RecordingProtocol wiki = new RecordingProtocol("wikipedia");

        SearchProviderFactory factory = factory(loader, List.of(serper, wiki));

        List<SearchProviderInstance> result = factory.assemble(SearchScope.of(TENANT, PROJECT));

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(SearchProviderInstance::id))
                .containsExactlyInAnyOrder("serper-main", "wiki-de");
        assertThat(serper.invocations.get()).isEqualTo(1);
        assertThat(wiki.invocations.get()).isEqualTo(1);
    }

    @Test
    void assemble_skips_endpoints_with_unknown_protocol() {
        SourceConfigLoader loader = mock(SourceConfigLoader.class);
        given(loader,
                doc("alpha", "unknown", "baseUrl", "https://example"),
                doc("serper-main", "serper"));

        RecordingProtocol serper = new RecordingProtocol("serper");
        SearchProviderFactory factory = factory(loader, List.of(serper));

        List<SearchProviderInstance> result = factory.assemble(SearchScope.of(TENANT, PROJECT));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("serper-main");
    }

    @Test
    void assemble_still_instantiates_endpoints_with_enabled_false() {
        // enabled=false is no longer a build-time skip — ZarniwoopGateService
        // applies it at dispatch time so the UI can override.
        SourceConfigLoader loader = mock(SourceConfigLoader.class);
        given(loader,
                doc("alpha", "serper", "enabled", false),
                doc("beta", "serper"));

        SearchProviderFactory factory = factory(loader, List.of(new RecordingProtocol("serper")));

        List<SearchProviderInstance> result = factory.assemble(SearchScope.of(TENANT, PROJECT));

        assertThat(result.stream().map(SearchProviderInstance::id))
                .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void assemble_caches_per_project_and_protocol_called_once() {
        SourceConfigLoader loader = mock(SourceConfigLoader.class);
        given(loader, doc("alpha", "serper"));

        RecordingProtocol serper = new RecordingProtocol("serper");
        SearchProviderFactory factory = factory(loader, List.of(serper));

        SearchScope scope = SearchScope.of(TENANT, PROJECT);
        factory.assemble(scope);
        factory.assemble(scope);
        factory.assemble(scope);

        assertThat(serper.invocations.get()).isEqualTo(1);
        verify(loader, times(1)).load(eq(TENANT), eq(PROJECT), eq(SourceConfigPaths.RESEARCH));
    }

    @Test
    void project_stop_evicts_cache_and_disposes_instances() {
        SourceConfigLoader loader = mock(SourceConfigLoader.class);
        given(loader, doc("alpha", "serper"));

        RecordingProtocol serper = new RecordingProtocol("serper");
        SearchProviderFactory factory = factory(loader, List.of(serper));

        SearchScope scope = SearchScope.of(TENANT, PROJECT);
        List<SearchProviderInstance> first = factory.assemble(scope);
        RecordingInstance instance = (RecordingInstance) first.get(0);

        factory.onProjectStop(new ProjectEnginesStopRequested(TENANT, PROJECT));

        assertThat(instance.disposed.get()).isTrue();

        factory.assemble(scope);
        assertThat(serper.invocations.get()).isEqualTo(2);
    }

    @Test
    void project_stop_for_unknown_project_is_safe() {
        SourceConfigLoader loader = mock(SourceConfigLoader.class);
        SearchProviderFactory factory = factory(loader, List.of());

        factory.onProjectStop(new ProjectEnginesStopRequested(TENANT, "no-such-project"));
        // no exception, no eviction
        verify(loader, never()).load(any(), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static final class RecordingProtocol implements SearchProtocol {

        private final String id;
        private final AtomicInteger invocations = new AtomicInteger();

        RecordingProtocol(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public Set<SearchModality> modalitiesSupported() {
            return Set.of(SearchModality.WEB);
        }
        @Override public Set<SearchTier> tiersSupported() {
            return Set.of(SearchTier.NORMAL);
        }

        @Override
        public SearchProviderInstance instantiate(ProviderInstanceConfig cfg) {
            invocations.incrementAndGet();
            return new RecordingInstance(cfg.instanceId());
        }
    }

    private static final class RecordingInstance implements SearchProviderInstance {

        private final String id;
        private final java.util.concurrent.atomic.AtomicBoolean disposed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        RecordingInstance(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public Set<SearchModality> modalities() { return Set.of(SearchModality.WEB); }
        @Override public Set<SearchDomain> domains() { return Set.of(SearchDomain.GENERAL); }
        @Override public Set<SearchTier> tiers() { return Set.of(SearchTier.NORMAL); }
        @Override public ProviderAvailability availability(SearchScope scope) {
            return ProviderAvailability.READY;
        }
        @Override public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            return Optional.empty();
        }
        @Override public SearchResult search(SearchRequest req, SearchScope scope) {
            throw new UnsupportedOperationException("not used in factory tests");
        }
        @Override public void dispose() { disposed.set(true); }
    }
}
