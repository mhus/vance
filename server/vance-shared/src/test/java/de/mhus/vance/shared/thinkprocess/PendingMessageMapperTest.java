package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.api.thinkprocess.ActiveInboxContext;
import de.mhus.vance.api.thinkprocess.BoundDocSelection;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The hop between the pending façade and the durable engine message.
 *
 * <p>These tests exist because of a specific failure: the mapping was written
 * twice, and a field added to one copy simply arrived as {@code null} at the far
 * end — no error, no log line, just a display name degraded to a login and an
 * inbox hint the engine never saw. There is one copy now, and this is the test
 * that notices when the next field forgets half of the round trip.
 */
class PendingMessageMapperTest {

    /**
     * The per-turn view context is the part that broke, so it is asserted field
     * by field rather than through {@code isEqualTo} on the whole object: what
     * matters is that each one is carried, and a shared-reference comparison
     * would pass even on a mapper that copied nothing.
     */
    @Test
    void toEngineMessage_carriesThePerTurnViewContext() {
        BoundDocSelection selection = BoundDocSelection.builder().from(3).to(11).build();
        PendingMessageDocument pending = PendingMessageDocument.builder()
                .at(Instant.parse("2026-08-24T10:15:30Z"))
                .idempotencyKey("idem-1")
                .type(PendingMessageType.USER_CHAT_INPUT)
                .fromUser("mara")
                .fromUserDisplayName("Mara Vance")
                .content("what is waiting on me?")
                .voiceMode(true)
                .attachmentDocumentIds(List.of("doc-a", "doc-b"))
                .activeApp(ActiveAppContext.builder().folder("apps/wiki").app("wiki").build())
                .boundDocumentId("bound-1")
                .boundDocSelection(selection)
                .activeInbox(ActiveInboxContext.builder()
                        .threadId("thread-1").messageId("msg-9").build())
                .build();

        EngineMessageDocument engine = PendingMessageMapper.toEngineMessage(
                pending, "target-1", "acme", "sender-1");

        assertThat(engine.getMessageId()).isEqualTo("idem-1");
        assertThat(engine.getTenantId()).isEqualTo("acme");
        assertThat(engine.getSenderProcessId()).isEqualTo("sender-1");
        assertThat(engine.getTargetProcessId()).isEqualTo("target-1");
        assertThat(engine.getCreatedAt()).isEqualTo(Instant.parse("2026-08-24T10:15:30Z"));
        assertThat(engine.getFromUser()).isEqualTo("mara");
        assertThat(engine.getFromUserDisplayName()).isEqualTo("Mara Vance");
        assertThat(engine.getContent()).isEqualTo("what is waiting on me?");
        assertThat(engine.getVoiceMode()).isTrue();
        assertThat(engine.getAttachmentDocumentIds()).containsExactly("doc-a", "doc-b");
        assertThat(engine.getActiveApp()).isNotNull();
        assertThat(engine.getActiveApp().getApp()).isEqualTo("wiki");
        assertThat(engine.getBoundDocumentId()).isEqualTo("bound-1");
        assertThat(engine.getBoundDocSelection()).isEqualTo(selection);
        assertThat(engine.getActiveInbox()).isNotNull();
        assertThat(engine.getActiveInbox().getThreadId()).isEqualTo("thread-1");
        assertThat(engine.getActiveInbox().getMessageId()).isEqualTo("msg-9");
    }

    @Test
    void roundTrip_returnsTheViewContextToTheEngine() {
        // The drain reads back through the other direction, so a field carried
        // one way and dropped the other is just as invisible.
        PendingMessageDocument pending = PendingMessageDocument.builder()
                .at(Instant.parse("2026-08-24T10:15:30Z"))
                .idempotencyKey("idem-2")
                .type(PendingMessageType.USER_CHAT_INPUT)
                .fromUser("mara")
                .fromUserDisplayName("Mara Vance")
                .activeInbox(ActiveInboxContext.builder().threadId("thread-2").build())
                .build();

        PendingMessageDocument back = PendingMessageMapper.toPendingMessage(
                PendingMessageMapper.toEngineMessage(pending, "target-1", "acme", ""));

        assertThat(back.getFromUserDisplayName()).isEqualTo("Mara Vance");
        assertThat(back.getActiveInbox()).isNotNull();
        assertThat(back.getActiveInbox().getThreadId()).isEqualTo("thread-2");
        assertThat(back.getIdempotencyKey()).isEqualTo("idem-2");
        assertThat(back.getAt()).isEqualTo(Instant.parse("2026-08-24T10:15:30Z"));
    }

    @Test
    void toEngineMessage_withoutIdempotencyKey_generatesAMessageId() {
        // The key is what makes a cross-pod retry a no-op at the receiver; a
        // message without one still needs an id, just not a stable one.
        PendingMessageDocument pending = PendingMessageDocument.builder()
                .type(PendingMessageType.PROCESS_EVENT)
                .sourceProcessId("p-1")
                .build();

        EngineMessageDocument engine = PendingMessageMapper.toEngineMessage(
                pending, "target-1", null, null);

        assertThat(engine.getMessageId()).isNotBlank();
        assertThat(engine.getTenantId()).isEmpty();
        assertThat(engine.getSenderProcessId()).isEmpty();
        assertThat(engine.getCreatedAt()).isNotNull();
    }
}
