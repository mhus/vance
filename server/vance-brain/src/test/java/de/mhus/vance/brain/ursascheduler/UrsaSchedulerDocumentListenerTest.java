package de.mhus.vance.brain.ursascheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UrsaSchedulerDocumentListenerTest {

    private UrsaSchedulerService schedulerService;
    private UrsaSchedulerDocumentListener listener;

    @BeforeEach
    void setUp() {
        schedulerService = mock(UrsaSchedulerService.class);
        listener = new UrsaSchedulerDocumentListener(schedulerService);
    }

    @Test
    void scheduler_path_triggers_refreshOne_with_decoded_name() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", "_vance/scheduler/nightly-rollup.yaml", "id-1"));

        verify(schedulerService, times(1))
                .refreshOne("acme", "_tenant", "nightly-rollup");
    }

    @Test
    void non_scheduler_path_is_ignored() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "mail-assistant", "documents/notes.md", "id-1"));

        verify(schedulerService, never()).refreshOne(any(), any(), any());
    }

    @Test
    void unparseable_scheduler_path_is_ignored() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", "_vance/scheduler/", "id-1"));

        verify(schedulerService, never()).refreshOne(any(), any(), any());
    }

    @Test
    void delete_event_for_scheduler_also_triggers_refreshOne() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Deleted(
                "acme", "_tenant", "_vance/scheduler/nightly-rollup.yaml", "id-1"));

        verify(schedulerService, times(1))
                .refreshOne("acme", "_tenant", "nightly-rollup");
    }

    @Test
    void service_exception_is_swallowed() {
        doThrow(new RuntimeException("yaml broken"))
                .when(schedulerService).refreshOne(eq("acme"), eq("_tenant"), eq("nightly-rollup"));

        // Must not throw — the publisher must not be unwound.
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", "_vance/scheduler/nightly-rollup.yaml", "id-1"));
    }
}
