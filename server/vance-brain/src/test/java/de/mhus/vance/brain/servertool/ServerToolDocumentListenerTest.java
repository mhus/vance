package de.mhus.vance.brain.servertool;

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

class ServerToolDocumentListenerTest {

    private ServerToolRegistry registry;
    private ServerToolDocumentListener listener;

    @BeforeEach
    void setUp() {
        registry = mock(ServerToolRegistry.class);
        listener = new ServerToolDocumentListener(registry);
    }

    @Test
    void serverTool_path_triggers_refreshOne_with_decoded_name() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", "_vance/server-tools/zoho_imap.yaml", "id-1"));

        verify(registry, times(1)).refreshOne("acme", "_tenant", "zoho_imap");
    }

    @Test
    void non_serverTool_path_is_ignored() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "mail-assistant", "documents/notes.md", "id-1"));

        verify(registry, never()).refreshOne(any(), any(), any());
    }

    @Test
    void unparseable_serverTool_path_is_ignored() {
        // Path is under the prefix but missing the .yaml suffix.
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", "_vance/server-tools/", "id-1"));

        verify(registry, never()).refreshOne(any(), any(), any());
    }

    @Test
    void delete_event_for_serverTool_also_triggers_refreshOne() {
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Deleted(
                "acme", "_tenant", "_vance/server-tools/zoho_imap.yaml", "id-1"));

        verify(registry, times(1)).refreshOne("acme", "_tenant", "zoho_imap");
    }

    @Test
    void registry_exception_is_swallowed() {
        doThrow(new RuntimeException("parse failed"))
                .when(registry).refreshOne(eq("acme"), eq("_tenant"), eq("zoho_imap"));

        // Must not throw — the publisher must not be unwound.
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Upserted(
                "acme", "_tenant", "_vance/server-tools/zoho_imap.yaml", "id-1"));
    }
}
