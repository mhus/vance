package de.mhus.vance.store.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
 * "Could not ask what this account owns" against "this account owns nothing".
 *
 * <p>The two used to be the same answer, and the difference costs money: an
 * owned-but-not-installed kit falls back to {@code OFFERED}, the screen turns
 * the Buy button on, and the purchase goes through the store account rather
 * than the link — so it succeeds, grants a second entitlement and issues a
 * second invoice. Nothing upstream catches it; {@code OrderService.create} has
 * no "already owns it" guard.
 */
@ExtendWith(MockitoExtension.class)
class StoreOverviewOwnershipTest {

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
    void unlistableLibrary_marksOwnershipUnknown() {
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue()));
        when(library.list(TENANT, PROJECT, USER))
                .thenThrow(new KitException("the store rejected that credential"));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        StoreOverviewService.SourceView view = firstView();

        // The shop window still renders — that part was right — but the row
        // must not be presented as a thing this account has not bought.
        assertThat(view.reachable()).isTrue();
        assertThat(view.ownershipKnown()).isFalse();
        assertThat(view.entries()).hasSize(1);
    }

    @Test
    void listableLibrary_marksOwnershipKnown() {
        givenSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue()));
        when(library.list(TENANT, PROJECT, USER)).thenReturn(List.of());
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        assertThat(firstView().ownershipKnown()).isTrue();
    }

    @Test
    void notSignedIn_leavesOwnershipKnown() {
        // There is no account whose entitlements could be unknown, and buying
        // needs one — so the question does not arise and the flag must not
        // fire, or every unsigned visitor would see a shop that refuses to
        // sell.
        givenNotSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(catalogue()));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        assertThat(firstView().ownershipKnown()).isTrue();
    }

    @Test
    void unreachableStore_marksOwnershipUnknownToo() {
        givenNotSignedIn();
        when(client.catalogue(any())).thenThrow(new KitException("connection refused"));

        StoreOverviewService.SourceView view = firstView();

        assertThat(view.reachable()).isFalse();
        assertThat(view.ownershipKnown()).isFalse();
    }

    @Test
    void aCatalogueEntryWithoutAVendor_doesNotLoseTheWholeOverview() {
        // Jackson leaves an absent field null whatever @NullMarked says, and
        // the sort at the end reads it. One such entry used to throw inside
        // Comparator.comparing and turn the whole screen into a 500 — every
        // other row included.
        givenNotSignedIn();
        when(client.catalogue(any())).thenReturn(List.of(
                new StoreClient.CatalogueEntry(
                        null, "orphan", "Orphan", null, null, null, "1.0.0", null,
                        null, 0L, null, null, List.of(), List.of(), null),
                catalogue()));
        when(recordStore.list(TENANT, PROJECT)).thenReturn(List.of());

        assertThat(firstView().entries())
                .extracting(StoreOverviewService.Entry::kitId)
                .containsExactly("orphan", "security");
    }

    private StoreOverviewService.SourceView firstView() {
        return service.overview(TENANT, PROJECT, USER).get(0);
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

    private static StoreClient.CatalogueEntry catalogue() {
        return new StoreClient.CatalogueEntry(
                "acme", "security", "Security", "a kit", "MIT", null, "2.0.0", null,
                new StoreClient.Score(4.5d, 12L), 1990L, "EUR", 365,
                List.of("security"), List.of("skills", "documents"), "acme.example");
    }
}
