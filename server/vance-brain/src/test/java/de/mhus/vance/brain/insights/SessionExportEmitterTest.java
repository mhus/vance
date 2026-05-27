package de.mhus.vance.brain.insights;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.llmtrace.LlmTraceDirection;
import de.mhus.vance.shared.llmtrace.LlmTraceDocument;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.prak.audit.PrakRunRecord;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure-format tests for {@link SessionExportEmitter}: the JSONL header,
 * the type discriminators, and the chronological ordering. The Mongo
 * lookups that hydrate {@code ExportData} live in the controller and
 * are covered by the broader insights tests — not duplicated here.
 */
class SessionExportEmitterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static SessionDocument session() {
        SessionDocument s = new SessionDocument();
        s.setId("sess-mongo-id");
        s.setSessionId("sess-1");
        s.setTenantId("tenant-a");
        s.setUserId("alice");
        s.setProjectId("proj-x");
        s.setStatus(SessionStatus.RUNNING);
        s.setCreatedAt(Instant.parse("2026-05-27T10:00:00Z"));
        s.setLastActivityAt(Instant.parse("2026-05-27T10:30:00Z"));
        return s;
    }

    private static ThinkProcessDocument process(String id, Instant createdAt, String engine) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(id);
        p.setTenantId("tenant-a");
        p.setSessionId("sess-1");
        p.setProjectId("proj-x");
        p.setName("p-" + id);
        p.setThinkEngine(engine);
        p.setStatus(ThinkProcessStatus.RUNNING);
        p.setCreatedAt(createdAt);
        return p;
    }

    private static ChatMessageDocument chat(String id, Instant at, ChatRole role, String content) {
        ChatMessageDocument c = new ChatMessageDocument();
        c.setId(id);
        c.setTenantId("tenant-a");
        c.setSessionId("sess-1");
        c.setThinkProcessId("p-1");
        c.setRole(role);
        c.setContent(content);
        c.setCreatedAt(at);
        return c;
    }

    private static MemoryDocument memory(String id, Instant at) {
        MemoryDocument m = new MemoryDocument();
        m.setId(id);
        m.setTenantId("tenant-a");
        m.setSessionId("sess-1");
        m.setThinkProcessId("p-1");
        m.setKind(MemoryKind.OTHER);
        m.setContent("memory body");
        m.setCreatedAt(at);
        return m;
    }

    private static LlmTraceDocument trace(String id, Instant at, LlmTraceDirection dir) {
        LlmTraceDocument t = new LlmTraceDocument();
        t.setId(id);
        t.setTenantId("tenant-a");
        t.setProcessId("p-1");
        t.setSessionId("sess-1");
        t.setDirection(dir);
        t.setSequence(1);
        t.setCreatedAt(at);
        return t;
    }

    private static MarvinNodeDocument marvinNode(String id, Instant at) {
        MarvinNodeDocument n = new MarvinNodeDocument();
        n.setId(id);
        n.setTenantId("tenant-a");
        n.setProcessId("p-1");
        n.setPosition(0);
        n.setGoal("do thing");
        n.setTaskKind(TaskKind.WORKER);
        n.setStatus(NodeStatus.PENDING);
        n.setCreatedAt(at);
        return n;
    }

    private static PrakRunRecord prakRun(String id, Instant at) {
        PrakRunRecord r = new PrakRunRecord();
        r.setId(id);
        r.setTenantId("tenant-a");
        r.setProjectId("proj-x");
        r.setSessionId("sess-1");
        r.setProcessId("p-1");
        r.setRunId("run-" + id);
        r.setTrigger("idle-tick");
        r.setCreatedAt(at);
        return r;
    }

    private List<JsonNode> emitAndParse(SessionExportEmitter.ExportData data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SessionExportEmitter.write(out, mapper, data);
        String[] lines = out.toString().split("\n");
        java.util.ArrayList<JsonNode> nodes = new java.util.ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            nodes.add(mapper.readTree(line));
        }
        return nodes;
    }

    @Test
    void emit_writesSessionMetaFirstAndCountsRollUp() throws Exception {
        SessionExportEmitter.ExportData data = new SessionExportEmitter.ExportData(
                session(),
                List.of(process("p-1", Instant.parse("2026-05-27T10:00:05Z"), "arthur")),
                List.of(
                        chat("c1", Instant.parse("2026-05-27T10:00:10Z"), ChatRole.USER, "hi"),
                        chat("c2", Instant.parse("2026-05-27T10:00:20Z"), ChatRole.ASSISTANT, "hello")),
                List.of(memory("m1", Instant.parse("2026-05-27T10:00:30Z"))),
                List.of(),
                List.of(),
                List.of());

        List<JsonNode> rows = emitAndParse(data);

        assertThat(rows.get(0).get("type").asString()).isEqualTo("session_meta");
        assertThat(rows.get(0).get("sessionId").asString()).isEqualTo("sess-1");
        assertThat(rows.get(0).get("processCount").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("messageCount").asInt()).isEqualTo(2);
        assertThat(rows.get(0).get("memoryCount").asInt()).isEqualTo(1);
        assertThat(rows.get(0).has("exportedAt")).isTrue();
    }

    @Test
    void emit_sortsAllNonMetaRowsByTimestampAscending() throws Exception {
        // Deliberately scrambled order — emitter must straighten it out
        // so consumers can rely on chronological replay.
        SessionExportEmitter.ExportData data = new SessionExportEmitter.ExportData(
                session(),
                List.of(process("p-1", Instant.parse("2026-05-27T10:00:05Z"), "arthur")),
                List.of(
                        chat("c-late", Instant.parse("2026-05-27T10:00:40Z"), ChatRole.ASSISTANT, "z"),
                        chat("c-early", Instant.parse("2026-05-27T10:00:10Z"), ChatRole.USER, "a")),
                List.of(memory("m-mid", Instant.parse("2026-05-27T10:00:25Z"))),
                List.of(trace("t-mid", Instant.parse("2026-05-27T10:00:20Z"), LlmTraceDirection.INPUT)),
                List.of(),
                List.of(prakRun("pr-late2", Instant.parse("2026-05-27T10:00:50Z"))));

        List<JsonNode> rows = emitAndParse(data);

        // First row is always session_meta — the chronological rows
        // start at index 1.
        List<String> typesInOrder = rows.subList(1, rows.size()).stream()
                .map(n -> n.get("type").asString())
                .toList();
        assertThat(typesInOrder).containsExactly(
                "process",    // 10:00:05
                "message",    // 10:00:10  c-early
                "llm_trace",  // 10:00:20
                "memory",     // 10:00:25
                "message",    // 10:00:40  c-late
                "prak_run");  // 10:00:50
    }

    @Test
    void emit_omitsEmptyOptionalFields() throws Exception {
        // A bare chat message with no tags / meta / archive ref should
        // not carry empty placeholder fields — that's noise for the
        // external consumer and bloats the file.
        ChatMessageDocument bare = chat("c1", Instant.parse("2026-05-27T10:00:10Z"),
                ChatRole.USER, "hi");
        bare.setTags(Set.of());
        bare.getMeta().clear();

        SessionExportEmitter.ExportData data = new SessionExportEmitter.ExportData(
                session(),
                List.of(),
                List.of(bare),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        List<JsonNode> rows = emitAndParse(data);
        JsonNode messageRow = rows.get(1);

        assertThat(messageRow.has("tags")).isFalse();
        assertThat(messageRow.has("meta")).isFalse();
        assertThat(messageRow.has("archivedInMemoryId")).isFalse();
        assertThat(messageRow.get("content").asString()).isEqualTo("hi");
    }

    @Test
    void emit_recordCarriesProcessIdForCorrelation() throws Exception {
        SessionExportEmitter.ExportData data = new SessionExportEmitter.ExportData(
                session(),
                List.of(process("p-1", Instant.parse("2026-05-27T10:00:05Z"), "marvin")),
                List.of(),
                List.of(),
                List.of(trace("t1", Instant.parse("2026-05-27T10:00:10Z"), LlmTraceDirection.OUTPUT)),
                List.of(marvinNode("n1", Instant.parse("2026-05-27T10:00:15Z"))),
                List.of());

        List<JsonNode> rows = emitAndParse(data);
        // Find the trace row and marvin-node row; both must carry
        // processId so a consumer can join them back to the process
        // record without external lookups.
        JsonNode trace = rows.stream()
                .filter(n -> "llm_trace".equals(n.get("type").asString()))
                .findFirst().orElseThrow();
        JsonNode marvin = rows.stream()
                .filter(n -> "marvin_node".equals(n.get("type").asString()))
                .findFirst().orElseThrow();

        assertThat(trace.get("processId").asString()).isEqualTo("p-1");
        assertThat(marvin.get("processId").asString()).isEqualTo("p-1");
    }

    @Test
    void buildExportFilename_isWindowsSafeAndIncludesSessionId() {
        String name = InsightsAdminController.buildExportFilename(
                "abc-123", Instant.parse("2026-05-27T13:45:00Z"));
        // Colons would break Windows filenames — must be replaced.
        assertThat(name).doesNotContain(":");
        assertThat(name).startsWith("session-abc-123-");
        assertThat(name).endsWith(".jsonl");
        assertThat(name).contains("2026-05-27T13-45-00Z");
    }

    @Test
    void buildExportFilename_sanitisesUnsafeSessionIdCharacters() {
        // A pathological session id with slashes / spaces must not
        // produce a filename that escapes the download folder.
        String name = InsightsAdminController.buildExportFilename(
                "a/b c\\d", Instant.parse("2026-05-27T13:45:00Z"));
        assertThat(name).doesNotContain("/");
        assertThat(name).doesNotContain("\\");
        assertThat(name).doesNotContain(" ");
        assertThat(name).contains("a_b_c_d");
    }
}
