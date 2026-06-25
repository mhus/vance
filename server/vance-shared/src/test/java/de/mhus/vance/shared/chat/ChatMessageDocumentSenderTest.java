package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.chat.ChatRole;
import org.junit.jupiter.api.Test;

/**
 * Locks down the multi-user-session sender + addressed-to-agent fields
 * on {@link ChatMessageDocument} — see
 * {@code planning/multi-user-sessions.md} §3.5.
 *
 * <p>Defaults are critical: legacy rows + ASSISTANT/SYSTEM turns must
 * remain "addressed" so the existing wake-on-every-message behaviour
 * survives untouched.
 */
class ChatMessageDocumentSenderTest {

    @Test
    void defaultConstructor_addressedToAgentTrueAndSenderFieldsNull() {
        ChatMessageDocument doc = new ChatMessageDocument();

        assertThat(doc.isAddressedToAgent()).isTrue();
        assertThat(doc.getSenderUserId()).isNull();
        assertThat(doc.getSenderDisplayName()).isNull();
    }

    @Test
    void builder_withoutSender_keepsAddressedDefaultTrue() {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .tenantId("t").sessionId("s").thinkProcessId("p")
                .role(ChatRole.ASSISTANT).content("hi")
                .build();

        assertThat(doc.isAddressedToAgent()).isTrue();
        assertThat(doc.getSenderUserId()).isNull();
        assertThat(doc.getSenderDisplayName()).isNull();
    }

    @Test
    void builder_userTurnWithSenderIdentity_persistsAllFields() {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .tenantId("t").sessionId("s").thinkProcessId("p")
                .role(ChatRole.USER).content("@ai hello")
                .senderUserId("alice")
                .senderDisplayName("Alice Smith")
                .addressedToAgent(true)
                .build();

        assertThat(doc.getRole()).isEqualTo(ChatRole.USER);
        assertThat(doc.getSenderUserId()).isEqualTo("alice");
        assertThat(doc.getSenderDisplayName()).isEqualTo("Alice Smith");
        assertThat(doc.isAddressedToAgent()).isTrue();
    }

    @Test
    void builder_backgroundTurn_marksAddressedFalse() {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .tenantId("t").sessionId("s").thinkProcessId("p")
                .role(ChatRole.USER).content("hey bob")
                .senderUserId("alice").senderDisplayName("Alice")
                .addressedToAgent(false)
                .build();

        assertThat(doc.isAddressedToAgent()).isFalse();
    }
}
