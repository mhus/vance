package de.mhus.vance.shared.session.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportedTurn;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ParsedImport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parses a small Claude-Code {@code *.jsonl} fixture into the import model. */
class ClaudeExportParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String FIXTURE = String.join("\n",
            "{\"type\":\"mode\",\"mode\":\"normal\"}",
            "{\"type\":\"ai-title\",\"aiTitle\":\"My Claude Session\"}",
            // string-content user turn that is pure command scaffolding → dropped
            "{\"type\":\"user\",\"timestamp\":\"2026-07-01T10:00:00Z\","
                    + "\"message\":{\"role\":\"user\",\"content\":\"<local-command-caveat>noise</local-command-caveat>\"}}",
            // slash-command string → kept compactly
            "{\"type\":\"user\",\"timestamp\":\"2026-07-01T10:00:01Z\","
                    + "\"message\":{\"role\":\"user\",\"content\":\"<command-name>/clear</command-name>\"}}",
            // real user text
            "{\"type\":\"user\",\"timestamp\":\"2026-07-01T10:00:05Z\","
                    + "\"message\":{\"role\":\"user\",\"content\":\"build me a parser\"}}",
            // assistant with thinking + text + tool_use
            "{\"type\":\"assistant\",\"timestamp\":\"2026-07-01T10:00:10Z\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":["
                    + "{\"type\":\"thinking\",\"thinking\":\"pondering\"},"
                    + "{\"type\":\"text\",\"text\":\"on it\"},"
                    + "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls -la\"}}"
                    + "]}}",
            // user turn carrying a tool_result → folded into content
            "{\"type\":\"user\",\"timestamp\":\"2026-07-01T10:00:12Z\","
                    + "\"message\":{\"role\":\"user\",\"content\":["
                    + "{\"type\":\"tool_result\",\"content\":\"file1\\nfile2\"}"
                    + "]}}",
            "{\"type\":\"last-prompt\",\"lastPrompt\":\"build me a parser\"}");

    @Test
    void parse_mapsTitleTurnsThinkingAndFoldsTools() throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        for (String line : FIXTURE.split("\n")) {
            if (!line.isBlank()) rows.add(mapper.readTree(line));
        }

        ParsedImport parsed = ClaudeExportParser.parse(mapper, rows);

        assertThat(parsed.title()).isEqualTo("My Claude Session");
        assertThat(parsed.chatProcess()).isNull();     // synthesised by importer
        assertThat(parsed.memories()).isEmpty();

        // caveat dropped; slash-command, user text, assistant, tool_result = 4 turns
        assertThat(parsed.turns()).hasSize(4);

        assertThat(parsed.turns().get(0).content()).isEqualTo("$ /clear");
        assertThat(parsed.turns().get(1).content()).isEqualTo("build me a parser");

        ImportedTurn assistant = parsed.turns().get(2);
        assertThat(assistant.role()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(assistant.thinking()).isEqualTo("pondering");
        assertThat(assistant.content()).contains("on it");
        assertThat(assistant.content()).contains("⚙ Bash(command=ls -la)");
        assertThat(assistant.tags()).contains("TOOL_CALL:Bash");

        ImportedTurn toolResult = parsed.turns().get(3);
        assertThat(toolResult.role()).isEqualTo(ChatRole.USER);
        assertThat(toolResult.content()).contains("↳ result:");
        assertThat(toolResult.content()).contains("file1");
    }
}
