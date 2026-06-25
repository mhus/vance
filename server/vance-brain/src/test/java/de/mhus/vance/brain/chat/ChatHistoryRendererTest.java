package de.mhus.vance.brain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

/**
 * The renderer is the single point where the multi-user display-name
 * prefix gets stamped onto a USER turn (see
 * {@code planning/multi-user-sessions.md} §5). Locks down:
 *
 * <ul>
 *   <li>USER with display name → {@code "<Name>: <content>"}</li>
 *   <li>USER without display name → content unchanged (legacy 1:1)</li>
 *   <li>ASSISTANT / SYSTEM → role-specific message, content unchanged</li>
 * </ul>
 */
class ChatHistoryRendererTest {

    @Test
    void userTurnWithDisplayName_isPrefixed() {
        ChatMessageDocument doc = userDoc("hello", "alice", "Alice Smith");

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc);

        assertThat(msg.singleText()).isEqualTo("Alice Smith: hello");
    }

    @Test
    void userTurnWithoutDisplayName_passesContentVerbatim() {
        ChatMessageDocument doc = userDoc("hello", "alice", null);

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc);

        assertThat(msg.singleText()).isEqualTo("hello");
    }

    @Test
    void userTurnWithBlankDisplayName_passesContentVerbatim() {
        ChatMessageDocument doc = userDoc("hello", "alice", "   ");

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc);

        assertThat(msg.singleText()).isEqualTo("hello");
    }

    @Test
    void assistantTurnIsUnchanged_regardlessOfSenderFields() {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.ASSISTANT);
        doc.setContent("here you go");
        doc.setSenderDisplayName("ignored");

        AiMessage msg = (AiMessage) ChatHistoryRenderer.toLangchain(doc);

        assertThat(msg.text()).isEqualTo("here you go");
    }

    @Test
    void systemTurnIsUnchanged() {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.SYSTEM);
        doc.setContent("session resumed");

        SystemMessage msg = (SystemMessage) ChatHistoryRenderer.toLangchain(doc);

        assertThat(msg.text()).isEqualTo("session resumed");
    }

    @Test
    void applySenderPrefix_isUsableStandalone() {
        ChatMessageDocument withName = userDoc("ignored", "bob", "Bob");
        ChatMessageDocument noName = userDoc("ignored", "bob", null);

        assertThat(ChatHistoryRenderer.applySenderPrefix(withName, "raw"))
                .isEqualTo("Bob: raw");
        assertThat(ChatHistoryRenderer.applySenderPrefix(noName, "raw"))
                .isEqualTo("raw");
        assertThat(ChatHistoryRenderer.applySenderPrefix(withName, null))
                .isEqualTo("Bob: ");
    }

    private static ChatMessageDocument userDoc(String content, String userId, String displayName) {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.USER);
        doc.setContent(content);
        doc.setSenderUserId(userId);
        doc.setSenderDisplayName(displayName);
        return doc;
    }
}
