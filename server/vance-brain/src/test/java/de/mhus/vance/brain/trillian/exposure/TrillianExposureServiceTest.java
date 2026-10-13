package de.mhus.vance.brain.trillian.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The exposure registry: who is exposed, how the config is cached, and what
 * a change to the hub document does to the cache. The fail-closed shapes —
 * missing and malformed documents — are part of the contract: a broken
 * config opens nothing.
 */
@ExtendWith(MockitoExtension.class)
class TrillianExposureServiceTest {

    private static final String TENANT = "acme";
    private static final String COLIN = "_trillian-colin-5127";

    @Mock
    private DocumentService documentService;

    @Mock
    private UserService userService;

    private MutableClock clock;
    private TrillianExposureService service;

    /** A clock the tests advance, because the cache TTL is the cross-pod coherence bound. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-13T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new TrillianExposureService(documentService, userService, clock);
    }

    @Test
    void directoryListsOnlyActiveTrilliansWithAConfig() {
        when(userService.all(TENANT))
                .thenReturn(List.of(
                        trillian(COLIN, UserStatus.ACTIVE),
                        trillian("_trillian-void", UserStatus.ACTIVE), // no config document
                        trillian("_trillian-archived-one", UserStatus.DISABLED), // not active
                        human("road.runner"))); // no Trillian
        givenConfig(COLIN, "exposedName: colin\nallowedCounterparts: [road.runner]\ndoors:\n  mcp: true\n");
        // void's hub has no document

        List<TrillianExposureService.Exposed> directory = service.directory(TENANT);

        assertThat(directory).hasSize(1);
        assertThat(directory.get(0).account()).isEqualTo(COLIN);
        assertThat(directory.get(0).config().exposedName()).isEqualTo("colin");
    }

    @Test
    void findByExposedNameResolvesTheAccountBehindIt() {
        when(userService.all(TENANT)).thenReturn(List.of(trillian(COLIN, UserStatus.ACTIVE)));
        givenConfig(COLIN, "exposedName: colin\nallowedCounterparts: [road.runner]\ndoors:\n  mcp: true\n");

        Optional<TrillianExposureService.Exposed> hit = service.findByExposedName(TENANT, "colin");

        assertThat(hit).isPresent();
        assertThat(hit.get().account()).isEqualTo(COLIN);
        assertThat(service.findByExposedName(TENANT, "marvin")).isEmpty();
    }

    @Test
    void malformedConfigMeansNotExposed_notAnException() {
        when(userService.all(TENANT)).thenReturn(List.of(trillian(COLIN, UserStatus.ACTIVE)));
        givenConfig(COLIN, "exposedName: [not, a, string]\n");

        assertThat(service.configOf(TENANT, COLIN)).isEmpty();
        assertThat(service.directory(TENANT)).isEmpty();
    }

    @Test
    void configIsCachedUntilTheTtlExpires() {
        givenConfig(COLIN, "exposedName: colin\nallowedCounterparts: [road.runner]\ndoors:\n  mcp: true\n");

        service.configOf(TENANT, COLIN);
        service.configOf(TENANT, COLIN);
        verify(documentService, times(1))
                .findByPath(TENANT, HomeBootstrapService.hubProjectName(COLIN), TrillianExposureService.CONFIG_PATH);

        clock.advance(TrillianExposureService.CACHE_TTL.plusSeconds(1));
        service.configOf(TENANT, COLIN);
        verify(documentService, times(2))
                .findByPath(TENANT, HomeBootstrapService.hubProjectName(COLIN), TrillianExposureService.CONFIG_PATH);
    }

    @Test
    void aConfigChangeEvictsTheCacheForThatTrillian() {
        when(userService.all(TENANT)).thenReturn(List.of(trillian(COLIN, UserStatus.ACTIVE)));
        givenConfig(COLIN, "exposedName: colin\nallowedCounterparts: [road.runner]\ndoors:\n  mcp: true\n");

        service.configOf(TENANT, COLIN);
        service.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                TENANT, HomeBootstrapService.hubProjectName(COLIN), TrillianExposureService.CONFIG_PATH, "doc-1"));
        givenConfig(COLIN, "exposedName: colin-renamed\nallowedCounterparts: [road.runner]\ndoors:\n  mcp: true\n");
        clock.advance(Duration.ZERO);

        assertThat(service.findByExposedName(TENANT, "colin-renamed")).isPresent();
    }

    @Test
    void headLimitAndInactivityOverridesReachTheCaller() {
        givenConfig(COLIN, "exposedName: colin\nheadLimit: 3\ninactivityDays: 7\n");

        assertThat(service.headLimitOf(TENANT, COLIN)).isEqualTo(3);
        assertThat(service.inactivityOf(TENANT, COLIN)).contains(Duration.ofDays(7));

        givenConfig(COLIN, "exposedName: colin\n");
        clock.advance(TrillianExposureService.CACHE_TTL.plusSeconds(1));

        assertThat(service.headLimitOf(TENANT, COLIN)).isZero();
        assertThat(service.inactivityOf(TENANT, COLIN)).isEmpty();
    }

    // ──────────────────── fixtures ────────────────────

    private void givenConfig(String account, String yaml) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(documentService.findByPath(
                        TENANT, HomeBootstrapService.hubProjectName(account), TrillianExposureService.CONFIG_PATH))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);
    }

    private static UserDocument trillian(String name, UserStatus status) {
        return UserDocument.builder()
                .name(name)
                .status(status)
                .serviceAccount(true)
                .build();
    }

    private static UserDocument human(String name) {
        return UserDocument.builder().name(name).status(UserStatus.ACTIVE).build();
    }
}
