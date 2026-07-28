package de.mhus.vance.shared.session.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportedTurn;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ParsedImport;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trips a hand-built {@link SessionExportEmitter.ExportData} through
 * the emitter and back through {@link VanceExportParser}, asserting the
 * import model preserves what the Vance format carries.
 */
class VanceExportParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parse_roundTripsChatProcessMessagesAndMemory() throws Exception {
        SessionDocument s = new SessionDocument();
        s.setId("sess-mongo");
        s.setSessionId("sess-1");
        s.setTenantId("t");
        s.setUserId("alice");
        s.setProjectId("p");
        s.setTitle("Original Title");
        s.setChatProcessId("proc-chat");
        s.setCreatedAt(Instant.parse("2026-05-27T10:00:00Z"));

        var proc = new de.mhus.vance.shared.thinkprocess.ThinkProcessDocument();
        proc.setId("proc-chat");
        proc.setTenantId("t");
        proc.setSessionId("sess-1");
        proc.setProjectId("p");
        proc.setName("chat");
        proc.setThinkEngine("arthur");
        proc.setRecipeName("arthur");
        proc.setStatus(ThinkProcessStatus.RUNNING);
        proc.getEngineParams().put("temperature", 0.3);
        proc.setPromptOverride("be terse");
        proc.setCreatedAt(Instant.parse("2026-05-27T10:00:01Z"));

        ChatMessageDocument u = msg("m1", ChatRole.USER, "hello", null,
                Instant.parse("2026-05-27T10:00:10Z"));
        ChatMessageDocument a = msg("m2", ChatRole.ASSISTANT, "hi there", "let me think",
                Instant.parse("2026-05-27T10:00:20Z"));

        MemoryDocument mem = new MemoryDocument();
        mem.setId("mem1");
        mem.setTenantId("t");
        mem.setSessionId("sess-1");
        mem.setThinkProcessId("proc-chat");
        mem.setKind(MemoryKind.ARCHIVED_CHAT);
        mem.setContent("summary");
        mem.getSourceRefs().add("m1");
        mem.setCreatedAt(Instant.parse("2026-05-27T10:00:05Z"));

        ParsedImport parsed = emitAndParse(new SessionExportEmitter.ExportData(
                s, List.of(proc), List.of(u, a), List.of(mem),
                List.of(), List.of(), List.of()));

        assertThat(parsed.title()).isEqualTo("Original Title");
        assertThat(parsed.chatProcess()).isNotNull();
        assertThat(parsed.chatProcess().engine()).isEqualTo("arthur");
        assertThat(parsed.chatProcess().recipeName()).isEqualTo("arthur");
        assertThat(parsed.chatProcess().promptOverride()).isEqualTo("be terse");
        assertThat(parsed.chatProcess().engineParams()).containsEntry("temperature", 0.3);

        assertThat(parsed.turns()).hasSize(2);
        ImportedTurn first = parsed.turns().get(0);
        assertThat(first.role()).isEqualTo(ChatRole.USER);
        assertThat(first.content()).isEqualTo("hello");
        assertThat(first.at()).isEqualTo(Instant.parse("2026-05-27T10:00:10Z"));
        ImportedTurn second = parsed.turns().get(1);
        assertThat(second.thinking()).isEqualTo("let me think");

        assertThat(parsed.memories()).hasSize(1);
        assertThat(parsed.memories().get(0).kind()).isEqualTo(MemoryKind.ARCHIVED_CHAT);
        assertThat(parsed.memories().get(0).sourceRefIds()).containsExactly("m1");
    }

    private static ChatMessageDocument msg(String id, ChatRole role, String content,
            String thinking, Instant at) {
        ChatMessageDocument c = new ChatMessageDocument();
        c.setId(id);
        c.setTenantId("t");
        c.setSessionId("sess-1");
        c.setThinkProcessId("proc-chat");
        c.setRole(role);
        c.setContent(content);
        c.setThinking(thinking);
        c.setCreatedAt(at);
        return c;
    }

    private ParsedImport emitAndParse(SessionExportEmitter.ExportData data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SessionExportEmitter.write(out, mapper, data);
        List<JsonNode> rows = new ArrayList<>();
        for (String line : out.toString().split("\n")) {
            if (!line.isBlank()) rows.add(mapper.readTree(line));
        }
        return VanceExportParser.parse(mapper, rows);
    }
}
