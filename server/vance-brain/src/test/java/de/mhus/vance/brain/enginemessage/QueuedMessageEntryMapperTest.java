package de.mhus.vance.brain.enginemessage;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.QueuedMessageEntry;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client-facing queue-entry mapping ("active message queue",
 * {@code planning/active-message-queue.md} §4 P3).
 *
 * <p>The one field worth pinning is the correlation key: it is
 * {@code messageId} on the durable form and {@code idempotencyKey} on the
 * legacy form, and the clients' "queued → picked up" badges live on it —
 * a drift there is a badge that never resolves, with no error anywhere.
 */
class QueuedMessageEntryMapperTest {

    @Test
    void durableForm_mapsMessageIdAndSender() {
        EngineMessageDocument doc = EngineMessageDocument.builder()
                .messageId("msg-1")
                .type(PendingMessageType.USER_CHAT_INPUT)
                .createdAt(Instant.parse("2026-10-04T10:00:00Z"))
                .fromUser("alice")
                .fromUserDisplayName("Alice")
                .content("hello")
                .build();

        QueuedMessageEntry entry = QueuedMessageEntryMapper.from(doc);

        assertThat(entry.getMessageId()).isEqualTo("msg-1");
        assertThat(entry.getType()).isEqualTo("USER_CHAT_INPUT");
        assertThat(entry.getCreatedAt()).isEqualTo(Instant.parse("2026-10-04T10:00:00Z"));
        assertThat(entry.getSenderUserId()).isEqualTo("alice");
        assertThat(entry.getSenderDisplayName()).isEqualTo("Alice");
        assertThat(entry.getContent()).isEqualTo("hello");
    }

    @Test
    void legacyForm_carriesTheMessageIdAsIdempotencyKey() {
        PendingMessageDocument doc = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.parse("2026-10-04T10:00:00Z"))
                .idempotencyKey("msg-2")
                .content("hello")
                .build();

        QueuedMessageEntry entry = QueuedMessageEntryMapper.from(doc);

        assertThat(entry.getMessageId()).isEqualTo("msg-2");
        assertThat(entry.getContent()).isEqualTo("hello");
    }

    @Test
    void lists_mapInOrder() {
        List<QueuedMessageEntry> entries = QueuedMessageEntryMapper.fromPending(List.of(
                PendingMessageDocument.builder()
                        .idempotencyKey("a")
                        .type(PendingMessageType.USER_CHAT_INPUT)
                        .build(),
                PendingMessageDocument.builder()
                        .idempotencyKey("b")
                        .type(PendingMessageType.USER_CHAT_INPUT)
                        .build()));

        assertThat(entries).extracting(QueuedMessageEntry::getMessageId).containsExactly("a", "b");
    }
}
