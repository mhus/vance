package de.mhus.vance.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.agrajag.AgrajagChecker;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchDomain;
import de.mhus.vance.toolpack.research.SearchHit;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ZarniwoopServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "alpha";
    private static final SearchScope SCOPE = SearchScope.of(TENANT, PROJECT);
    private static final ToolInvocationContext CTX =
            new ToolInvocationContext(TENANT, PROJECT, null, null, null);

    private SearchProviderFactory factory;
    private SettingService settings;
    private ToolHealthService healthService;
    private AgrajagChecker agrajag;
    private ObjectProvider<AgrajagChecker> agrajagProvider;
    private QuotaCache quotaCache;
    private ZarniwoopService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        factory = mock(SearchProviderFactory.class);
        settings = mock(SettingService.class);
        healthService = mock(ToolHealthService.class);
        agrajag = mock(AgrajagChecker.class);
        agrajagProvider = (ObjectProvider<AgrajagChecker>) mock(ObjectProvider.class);
        when(agrajagProvider.getIfAvailable()).thenReturn(agrajag);
        when(healthService.lookupActiveCooldown(
                anyString(), any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        quotaCache = mock(QuotaCache.class);
        when(quotaCache.get(any(), any())).thenReturn(Optional.empty());
        service = new ZarniwoopService(factory, settings, healthService,
                agrajagProvider, quotaCache, new ZarniwoopUsageCounter());
    }

    @Test
    void search_rejects_request_without_project_scope() {
        SearchRequest req = SearchRequest.normal("q", SearchModality.WEB, 5);
        SearchScope tenantOnly = new SearchScope(TENANT, "", null, null);
        assertThatThrownBy(() -> service.search(req, tenantOnly, CTX))
                .isInstanceOf(ZarniwoopException.class);
    }

    @Test
    void search_returns_unavailable_when_no_instances() {
        when(factory.assemble(eq(SCOPE))).thenReturn(List.of());

        SearchRequest req = SearchRequest.normal("q", SearchModality.WEB, 5);
        SearchResult result = service.search(req, SCOPE, CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("no provider instance");
    }

    @Test
    void search_returns_first_successful_result() {
        FakeInstance primary = new FakeInstance("primary",
                Behavior.success("primary"));
        when(factory.assemble(eq(SCOPE))).thenReturn(List.of(primary));
        when(settings.getStringValueCascade(
                eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.defaultKey(SearchModality.WEB))))
                .thenReturn("primary");

        SearchRequest req = SearchRequest.normal("q", SearchModality.WEB, 5);
        SearchResult result = service.search(req, SCOPE, CTX);

        assertThat(result.ok()).isTrue();
        assertThat(result.providerInstanceId()).isEqualTo("primary");
        assertThat(primary.calls).isEqualTo(1);
    }

    @Test
    void search_falls_back_after_hard_failure_and_routes_to_agrajag() {
        FakeInstance primary = new FakeInstance("primary",
                Behavior.throwHard(new RuntimeException("upstream 502")));
        FakeInstance secondary = new FakeInstance("secondary",
                Behavior.success("secondary"));
        when(factory.assemble(eq(SCOPE))).thenReturn(List.of(primary, secondary));
        when(settings.getStringValueCascade(
                eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.defaultKey(SearchModality.WEB))))
                .thenReturn("primary");
        when(settings.getStringValueCascade(
                eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.fallbackKey(SearchModality.WEB))))
                .thenReturn("secondary");

        SearchRequest req = SearchRequest.normal("q", SearchModality.WEB, 5);
        SearchResult result = service.search(req, SCOPE, CTX);

        assertThat(result.ok()).isTrue();
        assertThat(result.providerInstanceId()).isEqualTo("secondary");
        verify(agrajag).handle(
                eq(ZarniwoopSettings.cooldownSubject("primary", SearchModality.WEB)),
                any(Throwable.class), eq(CTX));
    }

    @Test
    void search_filters_out_instances_with_active_cooldown() {
        FakeInstance cooled = new FakeInstance("cooled", Behavior.success("cooled"));
        FakeInstance hot = new FakeInstance("hot", Behavior.success("hot"));
        when(factory.assemble(eq(SCOPE))).thenReturn(List.of(cooled, hot));
        when(settings.getStringValueCascade(
                eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.defaultKey(SearchModality.WEB))))
                .thenReturn("cooled");
        when(healthService.lookupActiveCooldown(
                eq(TENANT), eq(ToolHealthScope.PROJECT), eq(PROJECT),
                eq(ZarniwoopSettings.cooldownSubject("cooled", SearchModality.WEB)),
                any(), any(), any()))
                .thenReturn(Optional.of(new ToolHealthCooldown()));

        SearchRequest req = SearchRequest.normal("q", SearchModality.WEB, 5);
        SearchResult result = service.search(req, SCOPE, CTX);

        assertThat(result.ok()).isTrue();
        assertThat(result.providerInstanceId()).isEqualTo("hot");
        assertThat(cooled.calls).isEqualTo(0);
    }

    @Test
    void search_filters_out_unavailable_instances() {
        FakeInstance dead = new FakeInstance("dead", Behavior.success("dead"));
        dead.availability = ProviderAvailability.NO_CREDENTIALS;
        FakeInstance live = new FakeInstance("live", Behavior.success("live"));
        when(factory.assemble(eq(SCOPE))).thenReturn(List.of(dead, live));
        when(settings.getStringValueCascade(
                eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.defaultKey(SearchModality.WEB))))
                .thenReturn("dead");

        SearchRequest req = SearchRequest.normal("q", SearchModality.WEB, 5);
        SearchResult result = service.search(req, SCOPE, CTX);

        assertThat(result.providerInstanceId()).isEqualTo("live");
        assertThat(dead.calls).isEqualTo(0);
    }

    @Test
    void expert_with_pinned_instance_bypasses_default_cascade() {
        FakeInstance defaultInstance =
                new FakeInstance("default", Behavior.success("default"));
        FakeInstance pinned =
                new FakeInstance("pinned", Behavior.success("pinned"));
        when(factory.assemble(eq(SCOPE))).thenReturn(List.of(defaultInstance, pinned));
        // default is set but the pin must override.
        when(settings.getStringValueCascade(
                eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.defaultKey(SearchModality.WEB))))
                .thenReturn("default");

        SearchRequest req = new SearchRequest(
                "q", SearchModality.WEB, SearchTier.EXPERT, 5,
                null, "pinned", Map.of());
        FakeInstance.makeExpertCapable(pinned);
        SearchResult result = service.search(req, SCOPE, CTX);

        assertThat(result.providerInstanceId()).isEqualTo("pinned");
        assertThat(defaultInstance.calls).isEqualTo(0);
    }

    // ── helpers ───────────────────────────────────────────────────────

    private interface Behavior {
        SearchResult invoke(String instanceId, SearchRequest req);

        static Behavior success(String label) {
            return (id, req) -> new SearchResult(
                    req.query(), req.modality(), id, req.tier(),
                    List.of(new SearchHit("Title " + label,
                            "https://example/" + label,
                            "snippet", "src", req.modality(), null, Map.of())),
                    1, 0, null, null, Map.of());
        }

        static Behavior throwHard(RuntimeException e) {
            return (id, req) -> { throw e; };
        }
    }

    private static final class FakeInstance implements SearchProviderInstance {

        private final String id;
        private final Behavior behavior;
        int calls;
        ProviderAvailability availability = ProviderAvailability.READY;
        Set<SearchTier> tiers = Set.of(SearchTier.NORMAL);

        FakeInstance(String id, Behavior behavior) {
            this.id = id;
            this.behavior = behavior;
        }

        static void makeExpertCapable(FakeInstance fi) {
            fi.tiers = Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public Set<SearchModality> modalities() { return Set.of(SearchModality.WEB); }
        @Override public Set<SearchDomain> domains() { return Set.of(SearchDomain.GENERAL); }
        @Override public Set<SearchTier> tiers() { return tiers; }
        @Override public ProviderAvailability availability(SearchScope scope) {
            return availability;
        }
        @Override public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            return Optional.empty();
        }
        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            calls++;
            return behavior.invoke(id, req);
        }
    }
}
