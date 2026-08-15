package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

class SystemMessageMergerTest {

    @Test
    void merge_consecutiveSystemBlocks_joinedIntoOneWithBlankLine() {
        List<ChatMessage> in = List.of(
                SystemMessage.from("base prompt"),
                SystemMessage.from("memory block"),
                UserMessage.from("hello"));

        List<ChatMessage> out = SystemMessageMerger.mergeMessages(in);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) out.get(0)).text())
                .isEqualTo("base prompt\n\nmemory block");
        assertThat(out.get(1)).isEqualTo(UserMessage.from("hello"));
    }

    @Test
    void merge_singleSystemBlock_returnsInputUntouched() {
        List<ChatMessage> in = List.of(
                SystemMessage.from("base prompt"),
                UserMessage.from("hello"));

        // Same instance — callers rely on this to skip rebuilding the request.
        assertThat(SystemMessageMerger.mergeMessages(in)).isSameAs(in);
    }

    @Test
    void merge_noSystemBlock_returnsInputUntouched() {
        List<ChatMessage> in = List.of(UserMessage.from("hello"));

        assertThat(SystemMessageMerger.mergeMessages(in)).isSameAs(in);
    }

    @Test
    void merge_systemBlocksSeparatedByOtherRoles_mergedPerRunNotGlobally() {
        // A system message after the conversation started must not be
        // hoisted to the front — that would reorder the prompt.
        List<ChatMessage> in = List.of(
                SystemMessage.from("a"),
                SystemMessage.from("b"),
                UserMessage.from("q"),
                AiMessage.from("r"),
                SystemMessage.from("c"),
                SystemMessage.from("d"),
                UserMessage.from("q2"));

        List<ChatMessage> out = SystemMessageMerger.mergeMessages(in);

        assertThat(out).hasSize(5);
        assertThat(((SystemMessage) out.get(0)).text()).isEqualTo("a\n\nb");
        assertThat(out.get(1)).isEqualTo(UserMessage.from("q"));
        assertThat(out.get(2)).isEqualTo(AiMessage.from("r"));
        assertThat(((SystemMessage) out.get(3)).text()).isEqualTo("c\n\nd");
        assertThat(out.get(4)).isEqualTo(UserMessage.from("q2"));
    }

    @Test
    void merge_nineBlocksLikeArthur_collapseToOne() {
        // Arthur's real shape: base + eight appended blocks. Nine system
        // messages are what multiplied the tool manifest ninefold on
        // Ollama's glimmer renderer.
        List<ChatMessage> in = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            in.add(SystemMessage.from("block" + i));
        }
        in.add(UserMessage.from("go"));

        List<ChatMessage> out = SystemMessageMerger.mergeMessages(in);

        assertThat(out).hasSize(2);
        assertThat(((SystemMessage) out.get(0)).text())
                .isEqualTo("block0\n\nblock1\n\nblock2\n\nblock3\n\nblock4\n\n"
                        + "block5\n\nblock6\n\nblock7\n\nblock8");
    }

    @Test
    void merge_contentAndOrderPreservedVerbatim() {
        // The merge must not normalise whitespace: prompt caching and the
        // engines' own markdown both depend on the exact text.
        List<ChatMessage> in = List.of(
                SystemMessage.from("## Heading\n\n- item\n"),
                SystemMessage.from("  indented tail  "),
                UserMessage.from("x"));

        List<ChatMessage> out = SystemMessageMerger.mergeMessages(in);

        assertThat(((SystemMessage) out.get(0)).text())
                .isEqualTo("## Heading\n\n- item\n\n\n  indented tail  ");
    }
}
