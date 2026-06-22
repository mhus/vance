package de.mhus.vance.brain.documents;

import de.mhus.vance.api.documents.DocumentInvalidateNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.document.DocumentDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Pushes {@link DocumentInvalidateNotification} frames to the
 * session-WS that triggered a server-side document mutation.
 *
 * <p>Side-channel signal — Cortex tabs use it to refresh their
 * buffer when the agent in the same chat session edited the
 * displayed document. Other clients (foot, mobile) can ignore.
 *
 * <p>The frame travels the existing chat-WS tunnel and therefore
 * crosses pods without depending on Redis (which is the documents-
 * channel's transport for {@code DOCUMENT_CHANGED}). See
 * {@code planning/cortex-document-invalidation.md}.
 *
 * <p>Suppresses {@code _vance/logs/...} writes to avoid log-spam
 * traffic — same exclusion the {@code DocumentChangeRouter} applies
 * to its own broadcasts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentInvalidationEmitter {

    public static final String KIND_BODY = "body";
    public static final String KIND_NOTES = "notes";

    private final ClientEventPublisher events;

    /** Emit for a body-mutation (write / edit / append / replace-lines). */
    public void emitBody(@Nullable String sessionId, DocumentDocument doc) {
        emit(sessionId, doc, KIND_BODY);
    }

    /** Emit for a notes-mutation (add / update / delete). */
    public void emitNotes(@Nullable String sessionId, DocumentDocument doc) {
        emit(sessionId, doc, KIND_NOTES);
    }

    private void emit(@Nullable String sessionId, DocumentDocument doc, String kind) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (doc == null || doc.getId() == null) {
            return;
        }
        String path = doc.getPath();
        if (path != null && path.startsWith("_vance/logs/")) {
            return;
        }
        DocumentInvalidateNotification msg = DocumentInvalidateNotification.builder()
                .documentId(doc.getId())
                .path(path)
                .kind(kind)
                .build();
        boolean delivered = events.publish(
                sessionId, MessageType.DOCUMENT_INVALIDATE, msg);
        if (log.isTraceEnabled()) {
            log.trace("DOCUMENT_INVALIDATE session='{}' doc='{}' kind='{}' delivered={}",
                    sessionId, doc.getId(), kind, delivered);
        }
    }
}
