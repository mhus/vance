package de.mhus.vance.brain.tools.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.Tool;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Surface-level checks: which sub-tools the factory emits as a function
 * of the pack's {@code readonly} flag. Doesn't hit a real IMAP server —
 * just verifies the count/names/labels so the gate doesn't silently
 * regress.
 */
class ImapMailboxToolFactoryTest {

    private final ImapMailboxToolFactory factory =
            new ImapMailboxToolFactory(mock(SettingsSecretResolver.class));

    @Test
    void readonly_default_yields_four_read_tools() {
        Collection<Tool> tools = factory.create(packDoc(Map.of("host", "imap.example.com")));

        assertThat(toolNames(tools)).containsExactlyInAnyOrder(
                "imap__list_folders",
                "imap__list_messages",
                "imap__get_message",
                "imap__preview_message");
        assertThat(tools).allSatisfy(t -> assertThat(t.labels())
                .contains("read-only")
                .doesNotContain("write", "side-effect"));
    }

    @Test
    void readonly_false_adds_four_write_tools() {
        Collection<Tool> tools = factory.create(packDoc(Map.of(
                "host", "imap.example.com",
                "readonly", false)));

        assertThat(toolNames(tools)).containsExactlyInAnyOrder(
                "imap__list_folders",
                "imap__list_messages",
                "imap__get_message",
                "imap__preview_message",
                "imap__set_seen",
                "imap__set_flagged",
                "imap__move_message",
                "imap__delete_message");
    }

    @Test
    void write_tools_carry_side_effect_labels_read_tools_do_not() {
        Collection<Tool> tools = factory.create(packDoc(Map.of(
                "host", "imap.example.com",
                "readonly", false)));

        Map<String, Tool> byName = tools.stream()
                .collect(Collectors.toMap(Tool::name, t -> t));

        assertThat(byName.get("imap__list_folders").labels())
                .contains("read-only").doesNotContain("write", "side-effect");
        assertThat(byName.get("imap__set_seen").labels())
                .contains("write", "side-effect").doesNotContain("read-only");
        assertThat(byName.get("imap__delete_message").labels())
                .contains("write", "side-effect");
    }

    @Test
    void readonly_string_false_also_enables_write_tools() {
        Collection<Tool> tools = factory.create(packDoc(Map.of(
                "host", "imap.example.com",
                "readonly", "false")));

        assertThat(tools).hasSize(8);
    }

    private static ServerToolDocument packDoc(Map<String, Object> params) {
        return ServerToolDocument.builder()
                .name("imap")
                .type(ImapMailboxToolFactory.TYPE_ID)
                .parameters(new LinkedHashMap<>(params))
                .build();
    }

    private static List<String> toolNames(Collection<Tool> tools) {
        return tools.stream().map(Tool::name).collect(Collectors.toList());
    }
}
