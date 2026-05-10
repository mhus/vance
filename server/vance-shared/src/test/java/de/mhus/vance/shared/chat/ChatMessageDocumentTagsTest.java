package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code tags} field on {@link ChatMessageDocument}: default
 * value, builder behaviour, and that tag order is preserved (we use
 * {@link LinkedHashSet} for stable debug output).
 *
 * <p>Mongo round-trip is not exercised here — that is Spring's contract.
 * What we lock down is the in-JVM shape that the rest of the codebase
 * depends on.
 */
class ChatMessageDocumentTagsTest {

    @Test
    void defaultConstructor_initialisesEmptyMutableTagSet() {
        ChatMessageDocument doc = new ChatMessageDocument();

        assertThat(doc.getTags()).isNotNull().isEmpty();

        doc.getTags().add("FILE_EDIT");
        assertThat(doc.getTags()).containsExactly("FILE_EDIT");
    }

    @Test
    void builder_withoutTags_yieldsEmptyMutableSet() {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .tenantId("t")
                .sessionId("s")
                .thinkProcessId("p")
                .content("hi")
                .build();

        assertThat(doc.getTags()).isNotNull().isEmpty();
        doc.getTags().add("X");
        assertThat(doc.getTags()).containsExactly("X");
    }

    @Test
    void builder_withTags_preservesInsertionOrder() {
        Set<String> input = new LinkedHashSet<>();
        input.add("TOOL_CALL:client_file_edit");
        input.add("FILE_EDIT");
        input.add("RESOURCE:CLIENT_FILE:/abs/path/Foo.java");

        ChatMessageDocument doc = ChatMessageDocument.builder()
                .tenantId("t")
                .sessionId("s")
                .thinkProcessId("p")
                .content("hi")
                .tags(input)
                .build();

        assertThat(doc.getTags())
                .containsExactly(
                        "TOOL_CALL:client_file_edit",
                        "FILE_EDIT",
                        "RESOURCE:CLIENT_FILE:/abs/path/Foo.java");
    }

    @Test
    void instancesWithDifferentTagSets_areNotEqual() {
        ChatMessageDocument a = ChatMessageDocument.builder()
                .tenantId("t").sessionId("s").thinkProcessId("p").content("hi")
                .tags(new LinkedHashSet<>(Set.of("FILE_EDIT")))
                .build();
        ChatMessageDocument b = ChatMessageDocument.builder()
                .tenantId("t").sessionId("s").thinkProcessId("p").content("hi")
                .tags(new LinkedHashSet<>(Set.of("DOC_EDIT")))
                .build();

        assertThat(a).isNotEqualTo(b);
    }
}
