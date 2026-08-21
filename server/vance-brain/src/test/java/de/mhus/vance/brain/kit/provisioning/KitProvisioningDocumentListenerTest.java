package de.mhus.vance.brain.kit.provisioning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.document.DocumentChangedEvent;
import de.mhus.vance.shared.kit.KitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which document writes trigger provisioning, and which must not.
 *
 * <p>Takes the <b>raw</b> event on purpose: the routed one is dropped for
 * any project whose {@code homeNode} points at a dead pod, which is the
 * normal state of an {@code EPHEMERAL} project after a restart. See the
 * listener's javadoc.
 */
class KitProvisioningDocumentListenerTest {

    private KitProvisioningService service;
    private KitProvisioningDocumentListener listener;

    @BeforeEach
    void setUp() {
        service = mock(KitProvisioningService.class);
        listener = new KitProvisioningDocumentListener(service);
    }

    private static DocumentChangedEvent upserted(String path) {
        return new DocumentChangedEvent.Upserted("acme", "sales", path, "doc1");
    }

    @Test
    void provisioningDocumentChanged_triggersARun() {
        listener.onDocumentChanged(upserted(KitProvisioningLoader.PROVISIONING_PATH));

        verify(service).provisionCoalesced("acme", "sales");
    }

    @Test
    void provisioningDocumentDeleted_alsoTriggers() {
        // Additive-only means nothing is uninstalled, but the run should still
        // happen: the remaining entries are what now applies.
        listener.onDocumentChanged(new DocumentChangedEvent.Deleted(
                "acme", "sales", KitProvisioningLoader.PROVISIONING_PATH, "doc1"));

        verify(service).provisionCoalesced("acme", "sales");
    }

    @Test
    void installRecordWritten_doesNotTrigger() {
        // The loop this guards against: install writes a record under
        // _vance/kits/, a prefix filter would provision again, which writes the
        // record again.
        listener.onDocumentChanged(upserted("_vance/kits/installed/acme-crm.yaml"));

        verify(service, never()).provisionCoalesced(any(), any());
    }

    @Test
    void kitConfigWritten_doesNotTrigger() {
        listener.onDocumentChanged(upserted("_vance/kits/config/acme-crm.yaml"));

        verify(service, never()).provisionCoalesced(any(), any());
    }

    @Test
    void unrelatedDocument_doesNotTrigger() {
        listener.onDocumentChanged(upserted("notes/meeting.md"));

        verify(service, never()).provisionCoalesced(any(), any());
    }

    @Test
    void failureIsSwallowed() {
        doThrow(new KitException("boom")).when(service).provisionCoalesced(any(), any());

        // Listener contract rule 3: a failed provisioning must not travel back
        // into whatever wrote the document.
        listener.onDocumentChanged(upserted(KitProvisioningLoader.PROVISIONING_PATH));
    }
}
