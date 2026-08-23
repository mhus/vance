package de.mhus.vance.store.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the store surface checks, and against what.
 *
 * <p>Signing in, buying and reading one's own receipts belong to a person
 * ({@code specification/kit-store.md} §6, §11b) — they used to take
 * {@code Project ADMIN} on {@code _tenant}, which meant only tenant admins
 * had a store at all. Installing still writes into a project and still
 * takes ADMIN. And no endpoint may act through another person's hub, which
 * is where their link token lives.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoreAddonAuthorizationTest {

    private static final String TENANT = "acme";
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
        when(sources.configuredSources(TENANT)).thenReturn(List.of(library("devstore")));
        when(credentials.resolve(any(), any(), any(), any(), any()))
                .thenReturn(KitAccess.of(TENANT).withToken(TOKEN).withStoreAccount("acc_1"));
    }

    @Test
    void aPersonalCallTakesProjectReadRatherThanAdmin() {
        controller.connections(TENANT, "_tenant", request);

        verify(authority).enforce(request, new Resource.Project(TENANT, "_tenant"), Action.READ);
        verify(authority, never()).enforce(any(HttpServletRequest.class), any(), eq(Action.ADMIN));
    }

    @Test
    void theCallersOwnHubIsAllowed() {
        controller.connections(TENANT, "_user_" + USER, request);

        verify(authority).enforce(
                request, new Resource.Project(TENANT, "_user_" + USER), Action.READ);
    }

    @Test
    void anotherPersonsHubIsRefusedBeforeAnythingIsAsked() {
        // The cascade reads store.token.<source> through {projectId}; a
        // foreign hub carries somebody else's link, and a tenant admin
        // passes every project check that would otherwise be run.
        assertThatThrownBy(() -> controller.connections(TENANT, "_user_wile.e", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(authority, never()).enforce(any(HttpServletRequest.class), any(), any());
        verify(credentials, never()).resolve(any(), any(), any(), any(), any());
    }

    @Test
    void installStillTakesProjectAdmin() {
        when(recordStore.findByOrigin(any(), any(), any(), any())).thenReturn(null);

        controller.install(TENANT, "team", new StoreAddonController.InstallRequest(
                "devstore", "acme/widgets"), request);

        verify(authority).enforce(request, new Resource.Project(TENANT, "team"), Action.ADMIN);
    }

    // ──────────── one question per store per half minute ────────────

    @Test
    void identityIsAskedOnceForTheSameTokenAcrossEndpoints() {
        when(storeClient.identity(any(), eq(TOKEN))).thenReturn(
                new StoreClient.Identity("acc_1", "Someone", "ACTIVE", false, false, "LINK"));

        controller.connections(TENANT, "_tenant", request);
        controller.surfaces(TENANT, "_tenant", request);

        verify(storeClient, times(1)).identity(any(), eq(TOKEN));
    }

    @Test
    void anUnreachableStoreIsNotAskedAgainEither() {
        // The expensive case: without caching the failure, opening the area
        // pays the connect timeout once per endpoint, in series.
        when(storeClient.identity(any(), eq(TOKEN)))
                .thenThrow(new KitException("not reachable"));

        List<StoreAddonController.Connection> first =
                controller.connections(TENANT, "_tenant", request);
        controller.surfaces(TENANT, "_tenant", request);

        assertThat(first).singleElement()
                .satisfies(c -> assertThat(c.reachable()).isFalse());
        verify(storeClient, times(1)).identity(any(), eq(TOKEN));
    }

    private static KitSourceDto library(String id) {
        return KitSourceDto.builder()
                .id(id)
                .type(KitSourceType.LIBRARY)
                .url("http://localhost:9821/" + id)
                .build();
    }
}
