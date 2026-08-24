package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ActiveInboxContext;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The per-turn inbox hint has to survive the Mongo round-trip, for the same
 * reason the active-app hint does: the pending queue may be drained by another
 * pod, minutes later. A dropped hint is silent — the turn still works, the agent
 * just no longer knows what the reader was pointing at, and answers about the
 * wrong thread while sounding certain.
 *
 * <p>The legacy case matters too: rows written before this field existed decode
 * to {@code null}, not to an empty context, or the prompt would claim the reader
 * has thread {@code ''} open.
 */
class SteerMessageCodecActiveInboxTest {

    @Test
    void encodeDecode_preservesThreadAndPickedMessage() {
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.parse("2026-08-24T10:00:00Z"),
                null,
                "wile.coyote",
                "Wile",
                "what do you make of this one?",
                List.of(),
                false,
                null,
                null,
                null,
                ActiveInboxContext.builder().threadId("t1").messageId("m7").build());

        PendingMessageDocument encoded = SteerMessageCodec.toDocument(original);

        assertThat(encoded.getActiveInbox()).isNotNull();
        assertThat(encoded.getActiveInbox().getThreadId()).isEqualTo("t1");
        assertThat(encoded.getActiveInbox().getMessageId()).isEqualTo("m7");

        SteerMessage.UserChatInput decoded =
                (SteerMessage.UserChatInput) SteerMessageCodec.toMessage(encoded);

        assertThat(decoded.activeInbox()).isNotNull();
        assertThat(decoded.activeInbox().getThreadId()).isEqualTo("t1");
        assertThat(decoded.activeInbox().getMessageId()).isEqualTo("m7");
    }

    @Test
    void encodeDecode_threadWithoutPickedMessage_keepsMessageIdNull() {
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.now(), null, "wile.coyote", null, "and this thread?",
                List.of(), false, null, null, null,
                ActiveInboxContext.builder().threadId("t1").build());

        SteerMessage.UserChatInput decoded = (SteerMessage.UserChatInput)
                SteerMessageCodec.toMessage(SteerMessageCodec.toDocument(original));

        assertThat(decoded.activeInbox()).isNotNull();
        assertThat(decoded.activeInbox().getThreadId()).isEqualTo("t1");
        assertThat(decoded.activeInbox().getMessageId()).isNull();
    }

    @Test
    void encode_noInboxOpen_storesNull() {
        // The ten-argument constructor is what every other call site uses; it
        // must keep meaning "no inbox hint" rather than an empty one.
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.now(), null, "wile.coyote", null, "hi",
                List.of(), false, null, null, null);

        assertThat(SteerMessageCodec.toDocument(original).getActiveInbox()).isNull();
    }

    @Test
    void decode_legacyRowWithoutActiveInbox_yieldsNull() {
        PendingMessageDocument legacy = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser("wile.coyote")
                .content("hi")
                .build();

        SteerMessage.UserChatInput decoded =
                (SteerMessage.UserChatInput) SteerMessageCodec.toMessage(legacy);

        assertThat(decoded.activeInbox()).isNull();
    }
}
