package de.mhus.vance.brain.ursascheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.brain.project.ProjectActivationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UrsaSchedulerDocumentListenerTest {

    private UrsaSchedulerService schedulerService;
    private ProjectActivationRegistry activationRegistry;
    private UrsaSchedulerDocumentListener listener;

    @BeforeEach
    void setUp() {
        schedulerService = mock(UrsaSchedulerService.class);
        activationRegistry = new ProjectActivationRegistry();
        // Every case below is about path handling, so the project is active
        // here unless a test says otherwise.
        activationRegistry.activate("acme", "_tenant");
        activationRegistry.activate("acme", "mail-assistant");
        listener = new UrsaSchedulerDocumentListener(schedulerService, activationRegistry);
    }

    @Test
    void project_not_active_on_this_pod_is_ignored() {
        // The router refreshes the writing pod regardless of who holds the
        // lease. Registering a scheduler here would arm a second cron for the
        // same project on a pod that is not running it.
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "someone-elses-project",
                "_vance/scheduler/nightly-rollup.yaml", "id-1"));

        verify(schedulerService, never()).refreshOne(any(), any(), any());
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
