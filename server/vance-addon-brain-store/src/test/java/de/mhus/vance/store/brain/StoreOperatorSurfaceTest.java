package de.mhus.vance.store.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.KitSourceRegistry;
import de.mhus.vance.brain.kit.KitStoreCredentials;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who is offered the operator area.
 *
 * <p>It grants nothing — the store refuses anyone who is not on its own
 * operator list, and that list is a property rather than data. What this
 * decides is whether the surface is shown and served at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoreOperatorSurfaceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "_tenant";
    private static final String USER = "road.runner";

    @Mock private KitSourceRegistry sources;
    @Mock private StoreOverviewService overview;
    @Mock private StoreConnectionService connections;
    @Mock private KitService kitService;
    @Mock private KitRecordStore recordStore;
    @Mock private StoreClient storeClient;
    @Mock private KitStoreCredentials credentials;
    @Mock private RequestAuthority authority;
    @Mock private StoreDeveloperService developerService;
    @Mock private SettingService settings;

    private StoreAddonController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new StoreAddonController(sources, overview, connections, kitService,
                recordStore, storeClient, credentials, authority, developerService, settings);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(USER);
        when(sources.configuredSources(TENANT)).thenReturn(List.of(
                library("devstore"), library("other-store")));
    }

    @Test
    void surfaces_listsOnlyWhatThisBrainIsSetUpToOperate() {
        givenOperator("devstore", "true");
        givenOperator("other-store", null);

        assertThat(controller.surfaces(TENANT, PROJECT, request).operatorSources())
                .containsExactly("devstore");
    }

    @Test
    void surfaces_withoutTheSetting_isEmpty() {
        // The ordinary case. A button nobody can use puzzles everyone it
        // does not belong to and invites the rest to try it.
        givenOperator("devstore", null);
        givenOperator("other-store", null);

        assertThat(controller.surfaces(TENANT, PROJECT, request).operatorSources()).isEmpty();
    }

    @Test
    void theQueue_forAStoreThisBrainDoesNotOperate_isNotFound() {
        // Hiding a surface that still answers would make the setting a
        // decoration rather than a statement about this installation.
        givenOperator("devstore", null);

        assertThatThrownBy(() -> controller.operatorQueue(TENANT, PROJECT,
                new StoreAddonController.OperatorRequest("devstore", "op@example.com", "pw",
                        null, null, null, null),
                request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not set up to operate");
        // and no credential ever leaves this process
        verify(storeClient, never()).login(any(), any(), any());
    }

    @Test
    void aDecision_forAStoreThisBrainDoesNotOperate_isNotFound() {
        givenOperator("devstore", null);

        assertThatThrownBy(() -> controller.decide(TENANT, PROJECT, "approve-vendor",
                new StoreAddonController.OperatorRequest("devstore", "op@example.com", "pw",
                        "acmelabs", null, null, null),
                request))
                .isInstanceOf(ResponseStatusException.class);
        verify(storeClient, never()).approveVendor(any(), any(), any());
    }

    private void givenOperator(String sourceId, String value) {
        when(settings.getStringValueUserProjectCascade(
                eq(TENANT), eq(USER), eq(PROJECT), eq(null),
                eq(StoreAddonController.OPERATOR_KEY_PREFIX + sourceId)))
                .thenReturn(value);
    }

    private static KitSourceDto library(String id) {
        return KitSourceDto.builder()
                .id(id)
                .type(KitSourceType.LIBRARY)
                .url("http://localhost:9821")
                .build();
    }
}
