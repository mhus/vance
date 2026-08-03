package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThinkStreamSplitterTest {

    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder think = new StringBuilder();
    private final List<String> thinkChunks = new ArrayList<>();

    private void feed(ThinkStreamSplitter s, String... deltas) {
        for (String d : deltas) {
            s.accept(d, answer::append, t -> {
                think.append(t);
                thinkChunks.add(t);
            });
        }
        s.flush(answer::append, t -> {
            think.append(t);
            thinkChunks.add(t);
        });
    }

    @Test
    void plainText_withoutTags_passesThroughToAnswer() {
        feed(new ThinkStreamSplitter(), "Hello ", "world");
        assertThat(answer.toString()).isEqualTo("Hello world");
        assertThat(think.toString()).isEmpty();
    }

    @Test
    void singleDelta_splitsThinkFromAnswer() {
        feed(new ThinkStreamSplitter(), "<think>reasoning here</think>final answer");
        assertThat(think.toString()).isEqualTo("reasoning here");
        assertThat(answer.toString()).isEqualTo("final answer");
    }

    @Test
    void openTag_splitAcrossDeltas_isNotLeakedAsAnswer() {
        feed(new ThinkStreamSplitter(), "<thi", "nk>deep</think>done");
        assertThat(think.toString()).isEqualTo("deep");
        assertThat(answer.toString()).isEqualTo("done");
    }

    @Test
    void closeTag_splitAcrossDeltas_isNotLeakedAsThinking() {
        feed(new ThinkStreamSplitter(), "<think>abc</thi", "nk>xyz");
        assertThat(think.toString()).isEqualTo("abc");
        assertThat(answer.toString()).isEqualTo("xyz");
    }

    @Test
    void reasoningStreamedTokenByToken_reassembles() {
        ThinkStreamSplitter s = new ThinkStreamSplitter();
        feed(s, "<think>", "one ", "two ", "three", "</think>", "answer ", "text");
        assertThat(think.toString()).isEqualTo("one two three");
        assertThat(answer.toString()).isEqualTo("answer text");
    }

    @Test
    void unterminatedThink_flushesRemainderAsThinking() {
        feed(new ThinkStreamSplitter(), "<think>still thinking when the stream ends");
        assertThat(think.toString()).isEqualTo("still thinking when the stream ends");
        assertThat(answer.toString()).isEmpty();
    }

    @Test
    void answerBeforeThink_routedCorrectly() {
        feed(new ThinkStreamSplitter(), "prefix <think>mid</think> suffix");
        assertThat(answer.toString()).isEqualTo("prefix  suffix");
        assertThat(think.toString()).isEqualTo("mid");
    }

    @Test
    void loneAngleBracket_notMistakenForTag() {
        feed(new ThinkStreamSplitter(), "a < b and c > d");
        assertThat(answer.toString()).isEqualTo("a < b and c > d");
        assertThat(think.toString()).isEmpty();
    }
}
