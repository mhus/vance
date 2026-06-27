package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Per-message {@link ActiveAppContext active-app} hint must survive the
 * Mongo round-trip — without it a pod restart that replays the pending
 * queue would drop the prompt-inject signal for in-flight turns.
 *
 * <p>See {@code planning/apps-in-cortex-and-live.md} §5.
 */
class SteerMessageCodecActiveAppTest {

    @Test
    void encodeDecode_preservesActiveApp() {
        ActiveAppContext app = ActiveAppContext.builder()
                .folder("calendars/q3")
                .app("calendar")
                .build();
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.parse("2026-06-27T10:00:00Z"),
                null,
                "alice",
                "Alice",
                "what's next this week?",
                java.util.List.of(),
                false,
                app);

        PendingMessageDocument encoded = SteerMessageCodec.toDocument(original);

        assertThat(encoded.getActiveApp()).isNotNull();
        assertThat(encoded.getActiveApp().getFolder()).isEqualTo("calendars/q3");
        assertThat(encoded.getActiveApp().getApp()).isEqualTo("calendar");

        SteerMessage.UserChatInput decoded =
                (SteerMessage.UserChatInput) SteerMessageCodec.toMessage(encoded);

        assertThat(decoded.activeApp()).isNotNull();
        assertThat(decoded.activeApp().getFolder()).isEqualTo("calendars/q3");
        assertThat(decoded.activeApp().getApp()).isEqualTo("calendar");
    }

    @Test
    void encode_nullActiveApp_storesNull() {
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.now(),
                null,
                "alice",
                "no app open");

        PendingMessageDocument encoded = SteerMessageCodec.toDocument(original);

        assertThat(encoded.getActiveApp()).isNull();
    }

    @Test
    void decode_legacyRowWithoutActiveApp_yieldsNull() {
        PendingMessageDocument legacy = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser("alice")
                .content("hi")
                .build();

        SteerMessage.UserChatInput decoded =
                (SteerMessage.UserChatInput) SteerMessageCodec.toMessage(legacy);

        assertThat(decoded.activeApp()).isNull();
    }
}
