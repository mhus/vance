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
    void userTurn_collabActive_isPrefixed() {
        ChatMessageDocument doc = userDoc("hello", "alice", "Alice Smith");

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, true);

        assertThat(msg.singleText()).isEqualTo("Alice Smith: hello");
    }

    @Test
    void userTurn_collabInactive_passesVerbatim_evenWithDisplayName() {
        ChatMessageDocument doc = userDoc("hello", "alice", "Alice Smith");

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.singleText()).isEqualTo("hello");
    }

    @Test
    void userTurn_legacyOverload_defaultsToNoPrefix() {
        ChatMessageDocument doc = userDoc("hello", "alice", "Alice Smith");

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc);

        assertThat(msg.singleText()).isEqualTo("hello");
    }

    @Test
    void userTurn_collabActive_butNoDisplayName_passesVerbatim() {
        ChatMessageDocument doc = userDoc("hello", "alice", null);

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, true);

        assertThat(msg.singleText()).isEqualTo("hello");
    }

    @Test
    void userTurn_collabActive_blankDisplayName_passesVerbatim() {
        ChatMessageDocument doc = userDoc("hello", "alice", "   ");

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, true);

        assertThat(msg.singleText()).isEqualTo("hello");
    }

    @Test
    void assistantTurnIsUnchanged_regardlessOfSenderFields() {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.ASSISTANT);
        doc.setContent("here you go");
        doc.setSenderDisplayName("ignored");

        AiMessage msg = (AiMessage) ChatHistoryRenderer.toLangchain(doc, true);

        assertThat(msg.text()).isEqualTo("here you go");
    }

    @Test
    void systemTurnIsUnchanged() {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.SYSTEM);
        doc.setContent("session resumed");

        SystemMessage msg = (SystemMessage) ChatHistoryRenderer.toLangchain(doc, true);

        assertThat(msg.text()).isEqualTo("session resumed");
    }

    @Test
    void applySenderPrefix_collabActive_addsPrefix() {
        ChatMessageDocument withName = userDoc("ignored", "bob", "Bob");
        ChatMessageDocument noName = userDoc("ignored", "bob", null);

        assertThat(ChatHistoryRenderer.applySenderPrefix(withName, "raw", true))
                .isEqualTo("Bob: raw");
        assertThat(ChatHistoryRenderer.applySenderPrefix(noName, "raw", true))
                .isEqualTo("raw");
        assertThat(ChatHistoryRenderer.applySenderPrefix(withName, null, true))
                .isEqualTo("Bob: ");
    }

    @Test
    void applySenderPrefix_collabInactive_noPrefix() {
        ChatMessageDocument withName = userDoc("ignored", "bob", "Bob");

        assertThat(ChatHistoryRenderer.applySenderPrefix(withName, "raw", false))
                .isEqualTo("raw");
    }

    @Test
    void applySenderPrefix_stringOverload_followsCollabFlag() {
        assertThat(ChatHistoryRenderer.applySenderPrefix("Bob", "hi", true))
                .isEqualTo("Bob: hi");
        assertThat(ChatHistoryRenderer.applySenderPrefix("Bob", "hi", false))
                .isEqualTo("hi");
        assertThat(ChatHistoryRenderer.applySenderPrefix((String) null, "hi", true))
                .isEqualTo("hi");
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
