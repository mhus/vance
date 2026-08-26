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

    // ──────────── failed-tool-call replay (META_TOOL_FAILURES) ────────────

    @Test
    void assistantTurn_withFailedToolCalls_replaysThemBehindTheContent() {
        ChatMessageDocument doc = assistantDocWithFailures(
                "Done — the icon is in place.",
                "file_edit → No such file: /tmp/x.vue",
                "exec_run → permission denied");

        AiMessage msg = (AiMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.text())
                .startsWith("Done — the icon is in place.")
                .contains(ChatHistoryRenderer.FAILURE_HEADER)
                .contains("- file_edit → No such file: /tmp/x.vue")
                .contains("- exec_run → permission denied");
    }

    @Test
    void assistantTurn_withoutFailures_rendersByteForByteAsBefore() {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.ASSISTANT);
        doc.setContent("all good");

        AiMessage msg = (AiMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.text()).isEqualTo("all good");
    }

    @Test
    void assistantTurn_emptyContentWithFailures_startsWithTheFailureBlock() {
        ChatMessageDocument doc = assistantDocWithFailures("", "file_edit → gone");

        AiMessage msg = (AiMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.text()).startsWith(ChatHistoryRenderer.FAILURE_HEADER);
    }

    @Test
    void assistantTurn_malformedFailureMeta_isIgnored() {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.ASSISTANT);
        doc.setContent("body");
        doc.getMeta().put(ChatMessageDocument.META_TOOL_FAILURES, "not-a-list");

        AiMessage msg = (AiMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.text()).isEqualTo("body");
    }

    @Test
    void assistantTurn_failureListWithNonStringEntries_keepsOnlyTheUsableOnes() {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.ASSISTANT);
        doc.setContent("body");
        doc.getMeta().put(ChatMessageDocument.META_TOOL_FAILURES,
                java.util.Arrays.asList("file_edit → gone", 42, null, "  "));

        AiMessage msg = (AiMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.text())
                .contains("- file_edit → gone")
                .doesNotContain("42");
    }

    private static ChatMessageDocument assistantDocWithFailures(
            String content, String... failures) {
        ChatMessageDocument doc = new ChatMessageDocument();
        doc.setRole(ChatRole.ASSISTANT);
        doc.setContent(content);
        doc.getMeta().put(ChatMessageDocument.META_TOOL_FAILURES, java.util.List.of(failures));
        return doc;
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

    // ──────── selection-reference replay (META_SELECTION_REFERENCE) ────────

    @Test
    void userTurn_withSelectionReference_replaysItBehindTheContent() {
        ChatMessageDocument doc = userDoc("was steht da drin?", "alice", null);
        doc.getMeta().put(ChatMessageDocument.META_SELECTION_REFERENCE, java.util.Map.of(
                "label", "Von der Bettenerweiterung bis zur Stärkung",
                "vanceUri", "vance:/apps/newsfeed/_app.yaml?entry=hrafnagud%2F6928769",
                "url", "https://mehrnews.com/news/6928769"));

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.singleText())
                .startsWith("was steht da drin?")
                .contains(ChatHistoryRenderer.REFERENCE_HEADER)
                .contains("\"Von der Bettenerweiterung bis zur Stärkung\"")
                .contains("vance:/apps/newsfeed/_app.yaml?entry=hrafnagud%2F6928769")
                .contains("https://mehrnews.com/news/6928769");
    }

    @Test
    void userTurn_referenceWithOnlyAUrl_stillReplays() {
        ChatMessageDocument doc = userDoc("fasse das zusammen", "alice", null);
        doc.getMeta().put(ChatMessageDocument.META_SELECTION_REFERENCE, java.util.Map.of(
                "label", "A search hit", "url", "https://example.com/a"));

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.singleText()).contains("\"A search hit\" — https://example.com/a");
    }

    // A label is the *name* of the thing, not the thing: with no address
    // behind it the line would tell the model there was something without
    // any way to follow it.
    @Test
    void userTurn_referenceWithoutAnyAddress_isIgnored() {
        ChatMessageDocument doc = userDoc("hi", "alice", null);
        doc.getMeta().put(ChatMessageDocument.META_SELECTION_REFERENCE,
                java.util.Map.of("label", "just a name"));

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.singleText()).isEqualTo("hi");
    }

    @Test
    void userTurn_malformedReferenceMeta_isIgnored() {
        ChatMessageDocument doc = userDoc("hi", "alice", null);
        doc.getMeta().put(ChatMessageDocument.META_SELECTION_REFERENCE, "not-a-map");

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.singleText()).isEqualTo("hi");
    }

    @Test
    void userTurn_referenceAndDisplayNamePrefix_coexist() {
        ChatMessageDocument doc = userDoc("und das hier?", "alice", "Alice Smith");
        doc.getMeta().put(ChatMessageDocument.META_SELECTION_REFERENCE,
                java.util.Map.of("label", "Ein Eintrag", "url", "https://example.com/x"));

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, true);

        assertThat(msg.singleText())
                .startsWith("Alice Smith: und das hier?")
                .contains(ChatHistoryRenderer.REFERENCE_HEADER);
    }

    @Test
    void userTurn_withoutReference_rendersByteForByteAsBefore() {
        ChatMessageDocument doc = userDoc("plain", "alice", null);

        UserMessage msg = (UserMessage) ChatHistoryRenderer.toLangchain(doc, false);

        assertThat(msg.singleText()).isEqualTo("plain");
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
