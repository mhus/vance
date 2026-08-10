package de.mhus.vance.brain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The scrollback cap must not let a chatty worker evict the human
 * conversation. Since the history went session-wide, a plain newest-N cut
 * over the merged stream would do exactly that — one Frankie run emits an
 * interim note per tool batch — which is the data loss the session-wide
 * history was introduced to prevent (planning/process-visibility.md §5.3).
 */
class ChatHistoryScrollbackCapTest {

    private static final String CHAT = "chat-proc";
    private static final String WORKER = "worker-proc";

    @Test
    void underCap_returnsEverythingUntouched() {
        List<ChatMessageDocument> input = List.of(
                msg("a", CHAT), msg("b", WORKER), msg("c", CHAT));

        List<ChatMessageDocument> out =
                ChatHistoryController.applyScrollbackCap(input, CHAT, 10);

        assertThat(out).isSameAs(input);
    }

    @Test
    void chattyWorker_doesNotEvictTheOwnConversation() {
        // 5 human turns, then 50 worker notes. A naive tail(cap=10) would
        // return worker notes only.
        List<ChatMessageDocument> input = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            input.add(msg("own" + i, CHAT));
        }
        for (int i = 1; i <= 50; i++) {
            input.add(msg("note" + i, WORKER));
        }

        List<ChatMessageDocument> out =
                ChatHistoryController.applyScrollbackCap(input, CHAT, 10);

        assertThat(out).hasSize(10);
        assertThat(out).extracting(ChatMessageDocument::getId)
                .contains("own1", "own2", "own3", "own4", "own5")
                // remaining budget goes to the newest notes
                .contains("note46", "note50")
                .doesNotContain("note1");
    }

    @Test
    void ownConversationBeyondCap_keepsNewestOwn_andNoNotes() {
        List<ChatMessageDocument> input = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            input.add(msg("own" + i, CHAT));
        }
        input.add(msg("note1", WORKER));

        List<ChatMessageDocument> out =
                ChatHistoryController.applyScrollbackCap(input, CHAT, 5);

        assertThat(out).extracting(ChatMessageDocument::getId)
                .containsExactly("own8", "own9", "own10", "own11", "own12");
    }

    @Test
    void chronologicalOrderIsPreserved() {
        List<ChatMessageDocument> input = List.of(
                msg("own1", CHAT), msg("note1", WORKER),
                msg("own2", CHAT), msg("note2", WORKER),
                msg("own3", CHAT), msg("note3", WORKER));

        List<ChatMessageDocument> out =
                ChatHistoryController.applyScrollbackCap(input, CHAT, 4);

        // own1..3 fit, one budget slot left → newest note. Order stays as in
        // the input, not grouped per process.
        assertThat(out).extracting(ChatMessageDocument::getId)
                .containsExactly("own1", "own2", "own3", "note3");
    }

    @Test
    void withoutChatProcess_everythingCountsAsNotes() {
        List<ChatMessageDocument> input = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            input.add(msg("note" + i, WORKER));
        }

        List<ChatMessageDocument> out =
                ChatHistoryController.applyScrollbackCap(input, null, 2);

        assertThat(out).extracting(ChatMessageDocument::getId)
                .containsExactly("note5", "note6");
    }

    private static ChatMessageDocument msg(String id, String processId) {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .role(ChatRole.ASSISTANT)
                .content("c")
                .thinkProcessId(processId)
                .build();
        doc.setId(id);
        return doc;
    }
}
