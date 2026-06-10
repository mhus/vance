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
import de.mhus.vance.shared.settings.SettingService;
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

    @Test
    void assemble_requires_project_scope() {
        SettingService settings = mock(SettingService.class);
        SearchProviderFactory factory = new SearchProviderFactory(settings, List.of());

        SearchScope tenantOnly = new SearchScope(TENANT, "", null, null);

        assertThatThrownBy(() -> factory.assemble(tenantOnly))
                .isInstanceOf(ZarniwoopException.class)
                .hasMessageContaining("project");
    }

    @Test
    void assemble_returns_empty_when_no_endpoints_configured() {
        SettingService settings = mock(SettingService.class);
        when(settings.findByPrefixCascade(eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.PREFIX_ENDPOINT)))
                .thenReturn(Map.of());

        SearchProviderFactory factory = new SearchProviderFactory(settings, List.of());
        List<SearchProviderInstance> result = factory.assemble(SearchScope.of(TENANT, PROJECT));

        assertThat(result).isEmpty();
    }

    @Test
    void assemble_dispatches_to_protocol_for_each_endpoint() {
        SettingService settings = mock(SettingService.class);
        Map<String, String> endpointSettings = new LinkedHashMap<>();
        endpointSettings.put("research.endpoint.serper-main.protocol", "serper");
        endpointSettings.put("research.endpoint.serper-main.baseUrl", "https://google.serper.dev");
        endpointSettings.put("research.endpoint.wiki-de.protocol", "wikipedia");
        endpointSettings.put("research.endpoint.wiki-de.baseUrl",
                "https://de.wikipedia.org/w/api.php");
        when(settings.findByPrefixCascade(eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.PREFIX_ENDPOINT)))
                .thenReturn(endpointSettings);

        RecordingProtocol serper = new RecordingProtocol("serper");
        RecordingProtocol wiki = new RecordingProtocol("wikipedia");

        SearchProviderFactory factory = new SearchProviderFactory(
                settings, List.of(serper, wiki));

        List<SearchProviderInstance> result = factory.assemble(SearchScope.of(TENANT, PROJECT));

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(SearchProviderInstance::id))
                .containsExactlyInAnyOrder("serper-main", "wiki-de");
        assertThat(serper.invocations.get()).isEqualTo(1);
        assertThat(wiki.invocations.get()).isEqualTo(1);
    }

    @Test
    void assemble_skips_endpoints_with_unknown_protocol() {
        SettingService settings = mock(SettingService.class);
        Map<String, String> endpointSettings = new LinkedHashMap<>();
        endpointSettings.put("research.endpoint.alpha.protocol", "unknown");
        endpointSettings.put("research.endpoint.alpha.baseUrl", "https://example");
        endpointSettings.put("research.endpoint.serper-main.protocol", "serper");
        when(settings.findByPrefixCascade(eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.PREFIX_ENDPOINT)))
                .thenReturn(endpointSettings);

        RecordingProtocol serper = new RecordingProtocol("serper");
        SearchProviderFactory factory = new SearchProviderFactory(settings, List.of(serper));

        List<SearchProviderInstance> result = factory.assemble(SearchScope.of(TENANT, PROJECT));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("serper-main");
    }

    @Test
    void assemble_honours_explicit_enabled_false() {
        SettingService settings = mock(SettingService.class);
        Map<String, String> endpointSettings = new LinkedHashMap<>();
        endpointSettings.put("research.endpoint.alpha.protocol", "serper");
        endpointSettings.put("research.endpoint.alpha.enabled", "false");
        endpointSettings.put("research.endpoint.beta.protocol", "serper");
        when(settings.findByPrefixCascade(eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.PREFIX_ENDPOINT)))
                .thenReturn(endpointSettings);

        SearchProviderFactory factory = new SearchProviderFactory(
                settings, List.of(new RecordingProtocol("serper")));

        List<SearchProviderInstance> result = factory.assemble(SearchScope.of(TENANT, PROJECT));

        assertThat(result.stream().map(SearchProviderInstance::id))
                .containsExactly("beta");
    }

    @Test
    void assemble_caches_per_project_and_protocol_called_once() {
        SettingService settings = mock(SettingService.class);
        Map<String, String> endpointSettings = new LinkedHashMap<>();
        endpointSettings.put("research.endpoint.alpha.protocol", "serper");
        when(settings.findByPrefixCascade(eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.PREFIX_ENDPOINT)))
                .thenReturn(endpointSettings);

        RecordingProtocol serper = new RecordingProtocol("serper");
        SearchProviderFactory factory = new SearchProviderFactory(settings, List.of(serper));

        SearchScope scope = SearchScope.of(TENANT, PROJECT);
        factory.assemble(scope);
        factory.assemble(scope);
        factory.assemble(scope);

        assertThat(serper.invocations.get()).isEqualTo(1);
        verify(settings, times(1)).findByPrefixCascade(eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.PREFIX_ENDPOINT));
    }

    @Test
    void project_stop_evicts_cache_and_disposes_instances() {
        SettingService settings = mock(SettingService.class);
        Map<String, String> endpointSettings = new LinkedHashMap<>();
        endpointSettings.put("research.endpoint.alpha.protocol", "serper");
        when(settings.findByPrefixCascade(eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.PREFIX_ENDPOINT)))
                .thenReturn(endpointSettings);

        RecordingProtocol serper = new RecordingProtocol("serper");
        SearchProviderFactory factory = new SearchProviderFactory(settings, List.of(serper));

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
        SettingService settings = mock(SettingService.class);
        SearchProviderFactory factory = new SearchProviderFactory(settings, List.of());

        factory.onProjectStop(new ProjectEnginesStopRequested(TENANT, "no-such-project"));
        // no exception, no eviction
        verify(settings, never()).findByPrefixCascade(any(), any(), any(), any());
    }

    @Test
    void groupByEndpointId_splits_keys_on_first_dot() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("research.endpoint.serper-main.protocol", "serper");
        raw.put("research.endpoint.serper-main.baseUrl", "https://x");
        raw.put("research.endpoint.serper-main.timeoutMs", "5000");
        raw.put("research.endpoint.wiki-de.protocol", "wikipedia");
        raw.put("research.endpoint.bogus", "no-suffix");  // skipped

        Map<String, Map<String, String>> grouped = SearchProviderFactory.groupByEndpointId(raw);

        assertThat(grouped.keySet()).containsExactlyInAnyOrder("serper-main", "wiki-de");
        assertThat(grouped.get("serper-main"))
                .containsEntry("protocol", "serper")
                .containsEntry("baseUrl", "https://x")
                .containsEntry("timeoutMs", "5000");
        assertThat(grouped.get("wiki-de"))
                .containsEntry("protocol", "wikipedia");
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
