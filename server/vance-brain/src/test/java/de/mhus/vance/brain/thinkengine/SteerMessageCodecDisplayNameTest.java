package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the multi-user display-name attribution survives
 * encoding into the persistent {@link PendingMessageDocument} and
 * decoding back into {@link SteerMessage.UserChatInput} — see
 * {@code planning/multi-user-sessions.md} §5. Without the round-trip
 * a pod-restart would drop the display name when the pending queue
 * is replayed.
 */
class SteerMessageCodecDisplayNameTest {

    @Test
    void encodeDecode_preservesFromUserDisplayName() {
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.parse("2026-06-26T10:00:00Z"),
                null,
                "alice",
                "Alice Smith",
                "hey @ai do this",
                java.util.List.of(),
                false);

        PendingMessageDocument encoded = SteerMessageCodec.toDocument(original);

        assertThat(encoded.getFromUserDisplayName()).isEqualTo("Alice Smith");

        SteerMessage.UserChatInput decoded =
                (SteerMessage.UserChatInput) SteerMessageCodec.toMessage(encoded);

        assertThat(decoded.fromUser()).isEqualTo("alice");
        assertThat(decoded.fromUserDisplayName()).isEqualTo("Alice Smith");
        assertThat(decoded.content()).isEqualTo("hey @ai do this");
    }

    @Test
    void encode_nullDisplayName_storesNull() {
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.now(),
                null,
                "alice",
                "no display name here");

        PendingMessageDocument encoded = SteerMessageCodec.toDocument(original);

        assertThat(encoded.getFromUserDisplayName()).isNull();
    }

    @Test
    void decode_legacyRowWithoutDisplayName_yieldsNull() {
        PendingMessageDocument legacy = PendingMessageDocument.builder()
                .type(de.mhus.vance.shared.thinkprocess.PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser("alice")
                .content("hi")
                .build();

        SteerMessage.UserChatInput decoded =
                (SteerMessage.UserChatInput) SteerMessageCodec.toMessage(legacy);

        assertThat(decoded.fromUserDisplayName()).isNull();
    }
}
