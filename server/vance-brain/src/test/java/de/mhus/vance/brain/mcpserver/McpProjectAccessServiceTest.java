package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpProjectAccessServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT_A = "alpha";
    private static final String PROJECT_B = "beta";
    private static final String PATH = McpProjectAccessService.CONFIG_PATH;

    private static final String RW = """
            access:
              - name: open
                mode: rw
            """;
    private static final String RO = """
            access:
              - name: open
                mode: ro
            """;

    DocumentService documentService;
    MutableClock clock;
    McpProjectAccessService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        clock = new MutableClock(Instant.parse("2026-10-08T10:00:00Z"));
        service = new McpProjectAccessService(documentService, clock);
    }

    @Test
    void resolveEntry_isCachedWithinTtl() {
        config(PROJECT_A, RW);

        service.resolveEntry(TENANT, PROJECT_A, null);
        service.resolveEntry(TENANT, PROJECT_A, null);

        verify(documentService, times(1)).lookupCascade(TENANT, PROJECT_A, PATH);
    }

    @Test
    void resolveEntry_reloadsAfterTtl_evenWithoutChangeEvent() {
        // A pod that is neither writer nor lease holder never hears about
        // a project config change — the TTL bounds the stale window.
        config(PROJECT_A, RW);
        assertThat(service.resolveEntry(TENANT, PROJECT_A, null).mode()).isEqualTo(McpProjectAccessConfig.Mode.RW);

        config(PROJECT_A, RO);
        clock.advance(McpProjectAccessService.CACHE_TTL.plusSeconds(1));

        assertThat(service.resolveEntry(TENANT, PROJECT_A, null).mode()).isEqualTo(McpProjectAccessConfig.Mode.RO);
    }

    @Test
    void projectConfigChange_evictsOnlyThatProject() {
        config(PROJECT_A, RW);
        config(PROJECT_B, RW);
        service.resolveEntry(TENANT, PROJECT_A, null);
        service.resolveEntry(TENANT, PROJECT_B, null);

        service.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(TENANT, PROJECT_A, PATH, "d1"));
        service.resolveEntry(TENANT, PROJECT_A, null);
        service.resolveEntry(TENANT, PROJECT_B, null);

        verify(documentService, times(2)).lookupCascade(TENANT, PROJECT_A, PATH);
        verify(documentService, times(1)).lookupCascade(TENANT, PROJECT_B, PATH);
    }

    @Test
    void tenantConfigChange_evictsEveryProjectOfTheTenant() {
        // Projects without own config cached the _tenant fallback under
        // their own key — a _tenant change must reach all of them.
        when(documentService.lookupCascade(TENANT, PROJECT_A, PATH)).thenReturn(Optional.empty());
        assertThat(service.resolveEntry(TENANT, PROJECT_A, null)).isNull();

        config(PROJECT_A, RW);
        service.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(TENANT, "_tenant", PATH, "d1"));

        assertThat(service.resolveEntry(TENANT, PROJECT_A, null)).isNotNull();
    }

    @Test
    void tenantConfigChange_leavesOtherTenantsCached() {
        when(documentService.lookupCascade("other", PROJECT_A, PATH)).thenReturn(Optional.empty());
        service.resolveEntry("other", PROJECT_A, null);

        service.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(TENANT, "_tenant", PATH, "d1"));
        service.resolveEntry("other", PROJECT_A, null);

        verify(documentService, times(1)).lookupCascade("other", PROJECT_A, PATH);
    }

    private void config(String project, String yaml) {
        when(documentService.lookupCascade(TENANT, project, PATH))
                .thenReturn(Optional.of(new LookupResult(PATH, yaml, LookupResult.Source.PROJECT, null)));
    }

    /** Clock the test can move forward to cross the TTL. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
