package de.mhus.vance.store.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitLibraryEntryDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitLibraryService;
import de.mhus.vance.brain.kit.KitRecordStore;
import de.mhus.vance.brain.kit.KitSourceRegistry;
import de.mhus.vance.shared.kit.KitException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Joining what the store offers, what the account owns and what is
 * installed here — spec: {@code planning/kit-store.md} §3 S6.
 */
@ExtendWith(MockitoExtension.class)
class StoreOverviewServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String USER = "marvin";
    private static final String SOURCE_ID = "vancetope-library";
    private static final String URL = "https://library.vancetope.com";

    @Mock private KitSourceRegistry sources;
    @Mock private KitLibraryService library;
    @Mock private KitRecordStore recordStore;
    @Mock private StoreClient client;
    @Mock private StoreConnectionService connections;

    @InjectMocks private StoreOverviewService service;

    @BeforeEach
    void setUp() {
        when(sources.configuredSources(TENANT)).thenReturn(List.of(source()));
    }

    @Test
    void catalogueOnly_isOffered() {
        givenNotSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        assertThat(firstEntries())
                .singleElement()
                .extracting(StoreOverviewService.Entry::state)
                .isEqualTo(StoreOverviewService.EntryState.OFFERED);
    }

    @Test
    void ownedButNotInstalled_isOwned() {
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(library.list(TENANT, PROJECT, USER)).thenReturn(List.of(owned("2.0.0")));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        assertThat(firstEntries())
                .singleElement()
                .extracting(StoreOverviewService.Entry::state)
                .isEqualTo(StoreOverviewService.EntryState.OWNED);
    }

    @Test
    void installedAtTheOfferedVersion_isInstalled() {
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(library.list(TENANT, PROJECT, USER)).thenReturn(List.of(owned("2.0.0")));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of(record("library:2.0.0")));

        assertThat(firstEntries())
                .singleElement()
                .extracting(StoreOverviewService.Entry::state)
                .isEqualTo(StoreOverviewService.EntryState.INSTALLED);
    }

    @Test
    void installedAtAnotherVersion_isUpdatable() {
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(library.list(TENANT, PROJECT, USER)).thenReturn(List.of(owned("2.0.0")));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of(record("library:1.0.0")));

        StoreOverviewService.Entry entry = firstEntries().get(0);
        assertThat(entry.state()).isEqualTo(StoreOverviewService.EntryState.UPDATABLE);
        assertThat(entry.installedVersion()).isEqualTo("1.0.0");
        assertThat(entry.availableVersion()).isEqualTo("2.0.0");
    }

    @Test
    void anOwnedKit_keepsTheScoreFromTheCatalogue() {
        // The library listing knows nothing about ratings. Without carrying
        // the catalogue's score across, a kit would lose its stars the
        // moment it is bought — which is exactly when they matter least to
        // the buyer and most to everyone else reading the same screen.
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(library.list(TENANT, PROJECT, USER)).thenReturn(List.of(owned("2.0.0")));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        StoreOverviewService.Entry entry = firstEntries().get(0);
        assertThat(entry.averageStars()).isEqualTo(4.5d);
        assertThat(entry.ratingCount()).isEqualTo(12L);
    }

    @Test
    void anOwnedKit_keepsThePriceFromTheCatalogue() {
        // Same reason as the score: the library listing carries neither, and
        // somebody comparing a second seat still wants to see what it costs.
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(library.list(TENANT, PROJECT, USER)).thenReturn(List.of(owned("2.0.0")));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        StoreOverviewService.Entry entry = firstEntries().get(0);
        assertThat(entry.priceCents()).isEqualTo(1990L);
        assertThat(entry.currency()).isEqualTo("EUR");
    }

    @Test
    void aRecordFromAnotherSource_doesNotCountAsInstalled() {
        // Two libraries can carry a kit with the same path. Matching on the
        // path alone would report one as installed because the other is.
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(library.list(TENANT, PROJECT, USER)).thenReturn(List.of(owned("2.0.0")));
        KitInstalledRecordDto elsewhere = record("library:1.0.0");
        elsewhere.getOrigin().setUrl("https://other.example.com");
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of(elsewhere));

        assertThat(firstEntries())
                .singleElement()
                .extracting(StoreOverviewService.Entry::state)
                .isEqualTo(StoreOverviewService.EntryState.OWNED);
    }

    @Test
    void notSignedIn_doesNotAskTheLibrary() {
        // The library answers per link token. Asking without one would fail
        // and log noise for what is simply "nobody has signed in yet".
        givenNotSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        service.overview(TENANT, PROJECT, USER);

        verify(library, never()).list(any(), any(), any());
    }

    @Test
    void unreachableStore_saysSoInsteadOfLookingEmpty() {
        // "Nothing for sale" and "could not ask" are different answers, and
        // the screen has to be able to tell them apart.
        givenNotSignedIn();
        when(client.catalogue(any())).thenThrow(new KitException("connection refused"));

        List<StoreOverviewService.SourceView> views = service.overview(TENANT, PROJECT, USER);

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.reachable()).isFalse();
            assertThat(view.problem()).contains("connection refused");
            assertThat(view.entries()).isEmpty();
        });
    }

    @Test
    void unlistableLibrary_stillShowsTheCatalogue() {
        // A revoked or expired link should not blank out the shop window.
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue("security", "2.0.0")));
        when(library.list(TENANT, PROJECT, USER)).thenThrow(new KitException("rejected the token"));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        List<StoreOverviewService.SourceView> views = service.overview(TENANT, PROJECT, USER);

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.reachable()).isTrue();
            assertThat(view.entries()).singleElement()
                    .extracting(StoreOverviewService.Entry::state)
                    .isEqualTo(StoreOverviewService.EntryState.OFFERED);
        });
    }

    @Test
    void nonLibrarySources_areIgnored() {
        when(sources.configuredSources(TENANT)).thenReturn(List.of(
                KitSourceDto.builder().id("git").type(KitSourceType.GIT)
                        .url("https://github.com/acme/kits.git").build()));

        assertThat(service.overview(TENANT, PROJECT, USER)).isEmpty();
        verify(client, never()).catalogue(any());
    }

    private List<StoreOverviewService.Entry> firstEntries() {
        return service.overview(TENANT, PROJECT, USER).get(0).entries();
    }

    private void givenSignedIn() {
        when(connections.connectionOf(any(), any(), any(), any()))
                .thenReturn(new StoreConnectionService.Connection(SOURCE_ID, "acc_1"));
    }

    private void givenNotSignedIn() {
        when(connections.connectionOf(any(), any(), any(), any()))
                .thenReturn(new StoreConnectionService.Connection(SOURCE_ID, null));
    }

    private static KitSourceDto source() {
        return KitSourceDto.builder()
                .id(SOURCE_ID).type(KitSourceType.LIBRARY).url(URL).build();
    }

    private static StoreClient.CatalogueEntry catalogue(String kitId, String version) {
        return new StoreClient.CatalogueEntry(
                "acme", kitId, "Security", "a kit", "MIT", null, version, null,
                new StoreClient.Score(4.5d, 12L), 1990L, "EUR", 365);
    }

    private static KitLibraryEntryDto owned(String version) {
        return KitLibraryEntryDto.builder()
                .sourceId(SOURCE_ID)
                .sourceUrl(URL)
                .path("acme/security")
                .kitId("security")
                .vendor("acme")
                .displayName("Security")
                .version(version)
                .downloadable(true)
                .build();
    }

    private static KitInstalledRecordDto record(String commit) {
        return KitInstalledRecordDto.builder()
                .id("security-abc123")
                .kit(KitMetadataDto.builder().name("security").build())
                .origin(KitOriginDto.builder()
                        .url(URL)
                        .path("acme/security")
                        .commit(commit)
                        .build())
                .build();
    }
}
