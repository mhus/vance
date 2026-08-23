package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.discovery.ModelDiscoveryService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Controller-logic test for the on-demand refresh endpoint. Verifies
 * that the call delegates to {@link ModelCatalog#refresh()} and that
 * the {@link RequestAuthority#enforce} guard is invoked with the
 * Tenant resource + ADMIN action.
 */
class ModelCatalogAdminControllerTest {

    private static final String TENANT = "acme";

    private ModelCatalog catalog;
    private ModelDiscoveryService discoveryService;
    private RequestAuthority authority;
    private de.mhus.vance.brain.tools.budget.ObservedToolLimitRegistry observedToolLimits;
    private ModelCatalogAdminController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.findAllByPathPrefix(any())).thenReturn(List.of());
        catalog = new ModelCatalog(documentService, new ModelQuirks());
        discoveryService = mock(ModelDiscoveryService.class);
        authority = mock(RequestAuthority.class);
        observedToolLimits = new de.mhus.vance.brain.tools.budget.ObservedToolLimitRegistry();
        controller = new ModelCatalogAdminController(
                catalog, discoveryService, observedToolLimits, authority);
        request = mock(HttpServletRequest.class);
    }

    @Test
    void refresh_enforces_admin_authority_on_tenant() {
        controller.refresh(TENANT, request);

        verify(authority).enforce(eq(request),
                eq(new Resource.Tenant(TENANT)),
                eq(Action.ADMIN));
    }

    @Test
    void forgetToolLimits_dropsWhatThePodLearned_andSaysHowMuch() {
        // The registry has no TTL and keeps the smallest value ever seen,
        // so a mis-parsed number would otherwise cap that model until the
        // pod restarts. This is the way back.
        observedToolLimits.learnFrom("openai:gpt-x", "maximum length 128", 163);

        ModelCatalogAdminController.ForgetToolLimitsResult result =
                controller.forgetToolLimits(TENANT, request);

        assertThat(result.forgotten()).isEqualTo(1);
        assertThat(observedToolLimits.observedFor("openai:gpt-x")).isEmpty();
        verify(authority).enforce(eq(request),
                eq(new Resource.Tenant(TENANT)),
                eq(Action.ADMIN));
    }

    @Test
    void refresh_returns_counters_from_catalog() {
        ModelCatalog.RefreshResult result = controller.refresh(TENANT, request);

        assertThat(result.refreshedAt()).isNotNull();
        assertThat(result.bundledModelsLoaded()).isGreaterThan(50);
        assertThat(result.bundledProvidersLoaded()).isGreaterThanOrEqualTo(5);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }
}
