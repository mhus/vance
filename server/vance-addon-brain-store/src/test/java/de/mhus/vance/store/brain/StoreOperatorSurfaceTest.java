package de.mhus.vance.store.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitAccess;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.KitSourceRegistry;
import de.mhus.vance.brain.kit.KitStoreCredentials;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.kit.KitException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Who is offered the operator area.
 *
 * <p>The answer is the store's. It knows who may operate — from its own
 * configuration, where nothing that can write to a database reaches it —
 * and a brain keeping a local claim about that would be a second copy of
 * one truth.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoreOperatorSurfaceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "_tenant";
    private static final String USER = "road.runner";
    private static final String TOKEN = "vst_link";

    @Mock private KitSourceRegistry sources;
    @Mock private StoreOverviewService overview;
    @Mock private StoreConnectionService connections;
    @Mock private KitService kitService;
    @Mock private KitRecordStore recordStore;
    @Mock private StoreClient storeClient;
    @Mock private KitStoreCredentials credentials;
    @Mock private RequestAuthority authority;
    @Mock private StoreDeveloperService developerService;

    private StoreAddonController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new StoreAddonController(sources, overview, connections, kitService,
                recordStore, storeClient, credentials, authority, developerService);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(USER);
        when(sources.configuredSources(TENANT)).thenReturn(List.of(
                library("devstore"), library("other-store")));
        when(credentials.resolve(any(), any(), any(), any(), any()))
                .thenReturn(new KitAccess(TENANT, null, TOKEN, "acc_1", java.util.Map.of()));
    }

    @Test
    void surfaces_listsTheStoresThisAccountOperates() {
        givenIdentity("devstore", true);
        givenIdentity("other-store", false);

        assertThat(controller.surfaces(TENANT, PROJECT, request).operatorSources())
                .containsExactly("devstore");
    }

    @Test
    void surfaces_forAnOrdinaryAccount_isEmpty() {
        // The ordinary case, and the reason the tab is hidden at all: a
        // button nobody can use puzzles everyone it does not belong to and
        // invites the rest to try it.
        givenIdentity("devstore", false);
        givenIdentity("other-store", false);

        assertThat(controller.surfaces(TENANT, PROJECT, request).operatorSources()).isEmpty();
    }

    @Test
    void surfaces_reportsTheDeveloperRoleSeparately() {
        // Two roles, two lists. Somebody can publish at one store and
        // operate another, and the tab strip has to say which is which.
        givenIdentity("devstore", false, true);
        givenIdentity("other-store", true, false);

        StoreAddonController.Surfaces surfaces = controller.surfaces(TENANT, PROJECT, request);

        assertThat(surfaces.developerSources()).containsExactly("devstore");
        assertThat(surfaces.operatorSources()).containsExactly("other-store");
    }

    @Test
    void surfaces_withoutASignIn_asksNobody() {
        // No link, no question to ask — and no error either.
        when(credentials.resolve(any(), any(), any(), any(), any()))
                .thenReturn(new KitAccess(TENANT, null, null, null, java.util.Map.of()));

        StoreAddonController.Surfaces surfaces = controller.surfaces(TENANT, PROJECT, request);
        assertThat(surfaces.operatorSources()).isEmpty();
        assertThat(surfaces.developerSources()).isEmpty();
    }

    @Test
    void surfaces_whenAStoreCannotBeAsked_leavesItOut() {
        // An unreachable store is not one this account operates, and it is
        // not worth an error banner over a tab — the shop window already
        // says the store could not be reached.
        when(storeClient.identity(any(), eq(TOKEN)))
                .thenThrow(new KitException("not reachable"));

        assertThat(controller.surfaces(TENANT, PROJECT, request).operatorSources()).isEmpty();
    }

    private void givenIdentity(String sourceId, boolean operator) {
        givenIdentity(sourceId, operator, false);
    }

    private void givenIdentity(String sourceId, boolean operator, boolean vendor) {
        when(storeClient.identity(
                org.mockito.ArgumentMatchers.argThat(
                        source -> source != null && sourceId.equals(source.getId())),
                eq(TOKEN)))
                .thenReturn(new StoreClient.Identity(
                        "acc_1", "Someone", "ACTIVE", operator, vendor, "LINK"));
    }

    private static KitSourceDto library(String id) {
        return KitSourceDto.builder()
                .id(id)
                .type(KitSourceType.LIBRARY)
                .url("http://localhost:9821/" + id)
                .build();
    }
}
