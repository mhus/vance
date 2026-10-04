package de.mhus.vance.brain.enginemessage;

import de.mhus.vance.api.thinkprocess.QueuedMessageEntry;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import java.util.List;

/**
 * Shared mapping into the client-facing {@link QueuedMessageEntry} — the
 * display half of the "active message queue"
 * ({@code planning/active-message-queue.md} §4 P3).
 *
 * <p>One place for the field list on purpose: the queue is read in two
 * shapes (the {@code process-queue} notification carries
 * {@link EngineMessageDocument}s from the change event, the
 * {@code process-inbox} read returns {@link PendingMessageDocument}s from
 * the service façade) and a drift between the two would show as a badge
 * that never resolves. The messageId lives as {@code messageId} on the
 * durable form and as {@code idempotencyKey} on the legacy form — the only
 * field that needs translating at all.
 */
public final class QueuedMessageEntryMapper {

    private QueuedMessageEntryMapper() {}

    public static List<QueuedMessageEntry> from(List<EngineMessageDocument> docs) {
        return docs.stream().map(QueuedMessageEntryMapper::from).toList();
    }

    public static List<QueuedMessageEntry> fromPending(List<PendingMessageDocument> docs) {
        return docs.stream().map(QueuedMessageEntryMapper::from).toList();
    }

    public static QueuedMessageEntry from(EngineMessageDocument doc) {
        return QueuedMessageEntry.builder()
                .messageId(doc.getMessageId())
                .type(doc.getType() == null ? null : doc.getType().name())
                .createdAt(doc.getCreatedAt())
                .senderUserId(doc.getFromUser())
                .senderDisplayName(doc.getFromUserDisplayName())
                .content(doc.getContent())
                .build();
    }

    public static QueuedMessageEntry from(PendingMessageDocument doc) {
        return QueuedMessageEntry.builder()
                .messageId(doc.getIdempotencyKey())
                .type(doc.getType() == null ? null : doc.getType().name())
                .createdAt(doc.getAt())
                .senderUserId(doc.getFromUser())
                .senderDisplayName(doc.getFromUserDisplayName())
                .content(doc.getContent())
                .build();
    }
}
