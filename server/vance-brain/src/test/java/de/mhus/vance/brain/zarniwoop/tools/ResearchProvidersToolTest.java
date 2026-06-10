package de.mhus.vance.brain.zarniwoop.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.zarniwoop.SearchProviderFactory;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchDomain;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResearchProvidersToolTest {

    private static final ToolInvocationContext CTX_WITH_PROJECT =
            new ToolInvocationContext("acme", "alpha", null, null, null);

    @Test
    void invoke_without_project_scope_raises_ToolException() {
        SearchProviderFactory factory = mock(SearchProviderFactory.class);
        ResearchProvidersTool tool = new ResearchProvidersTool(factory);
        ToolInvocationContext noProject =
                new ToolInvocationContext("acme", null, null, null, null);

        assertThatThrownBy(() -> tool.invoke(Map.of(), noProject))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("project");
    }

    @Test
    void invoke_with_empty_factory_returns_empty_list() {
        SearchProviderFactory factory = mock(SearchProviderFactory.class);
        when(factory.assemble(org.mockito.ArgumentMatchers.any(SearchScope.class)))
                .thenReturn(List.of());

        ResearchProvidersTool tool = new ResearchProvidersTool(factory);
        Map<String, Object> result = tool.invoke(Map.of(), CTX_WITH_PROJECT);

        assertThat(result).containsEntry("count", 0);
        assertThat((List<?>) result.get("instances")).isEmpty();
    }

    @Test
    void invoke_lists_instances_with_metadata() {
        SearchProviderFactory factory = mock(SearchProviderFactory.class);
        when(factory.assemble(org.mockito.ArgumentMatchers.any(SearchScope.class)))
                .thenReturn(List.of(
                        new FakeInstance("serper-main", "Serper Main",
                                Set.of(SearchModality.WEB, SearchModality.IMAGE),
                                ProviderAvailability.READY,
                                Optional.of(new QuotaStatus(1552L, 2500L, null, null))),
                        new FakeInstance("wiki-de", "Wikipedia DE",
                                Set.of(SearchModality.INTERNAL_DOC),
                                ProviderAvailability.READY,
                                Optional.empty())));

        ResearchProvidersTool tool = new ResearchProvidersTool(factory);
        Map<String, Object> result = tool.invoke(Map.of(), CTX_WITH_PROJECT);

        assertThat(result).containsEntry("count", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("instances");
        assertThat(rows).hasSize(2);

        Map<String, Object> serperRow = rows.get(0);
        assertThat(serperRow).containsEntry("id", "serper-main");
        assertThat(serperRow).containsEntry("availability", "READY");
        @SuppressWarnings("unchecked")
        List<String> serperModalities = (List<String>) serperRow.get("modalities");
        assertThat(serperModalities).containsExactly("IMAGE", "WEB");   // alphabetical
        @SuppressWarnings("unchecked")
        Map<String, Object> serperQuota = (Map<String, Object>) serperRow.get("quota");
        assertThat(serperQuota)
                .containsEntry("remaining", 1552L)
                .containsEntry("limit", 2500L);

        Map<String, Object> wikiRow = rows.get(1);
        assertThat(wikiRow).containsEntry("id", "wiki-de");
        assertThat(wikiRow).doesNotContainKey("quota");
    }

    @Test
    void invoke_handles_availability_exception_gracefully() {
        SearchProviderFactory factory = mock(SearchProviderFactory.class);
        SearchProviderInstance broken = new FakeInstance(
                "broken", "Broken",
                Set.of(SearchModality.WEB),
                ProviderAvailability.READY,
                Optional.empty()) {
            @Override
            public ProviderAvailability availability(SearchScope scope) {
                throw new RuntimeException("setting service down");
            }
        };
        when(factory.assemble(org.mockito.ArgumentMatchers.any(SearchScope.class)))
                .thenReturn(List.of(broken));

        ResearchProvidersTool tool = new ResearchProvidersTool(factory);
        Map<String, Object> result = tool.invoke(Map.of(), CTX_WITH_PROJECT);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("instances");
        assertThat(rows.get(0)).containsEntry("availability", "DISABLED");
    }

    @Test
    void tool_is_deferred_and_read_only() {
        ResearchProvidersTool tool = new ResearchProvidersTool(mock(SearchProviderFactory.class));
        assertThat(tool.name()).isEqualTo("research_providers");
        assertThat(tool.primary()).isFalse();
        assertThat(tool.deferred()).isTrue();
        assertThat(tool.labels()).contains("read-only");
        assertThat(tool.searchHint()).isNotBlank();
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static class FakeInstance implements SearchProviderInstance {
        private final String id;
        private final String displayName;
        private final Set<SearchModality> modalities;
        private final ProviderAvailability availability;
        private final Optional<QuotaStatus> quota;

        FakeInstance(String id, String displayName,
                     Set<SearchModality> modalities,
                     ProviderAvailability availability,
                     Optional<QuotaStatus> quota) {
            this.id = id;
            this.displayName = displayName;
            this.modalities = modalities;
            this.availability = availability;
            this.quota = quota;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return displayName; }
        @Override public Set<SearchModality> modalities() { return modalities; }
        @Override public Set<SearchDomain> domains() { return Set.of(SearchDomain.GENERAL); }
        @Override public Set<SearchTier> tiers() { return Set.of(SearchTier.NORMAL); }
        @Override public ProviderAvailability availability(SearchScope scope) {
            return availability;
        }
        @Override public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            return quota;
        }
        @Override public SearchResult search(SearchRequest req, SearchScope scope) {
            throw new UnsupportedOperationException();
        }
    }
}
