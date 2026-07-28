package de.mhus.vance.shared.session.exchange;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.session.SessionMetadataPatchRequest;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.llmtrace.LlmTraceDocument;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.prak.audit.PrakRunRecord;
import de.mhus.vance.shared.prak.audit.PrakRunService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ChatProcessSpec;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportFormat;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportRequest;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportResult;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportedMemory;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportedTurn;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ParsedImport;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Reads and writes whole sessions across the shared persistence layer.
 *
 * <p>This is the single home for session export/import. Because every
 * entity and its owning service lives in {@code vance-shared}, both
 * callers reach it directly without a Brain round-trip:
 * <ul>
 *   <li>the Brain export endpoint (web UI download) delegates its
 *       hydration here,</li>
 *   <li>the anus {@code session} commands call it against the local
 *       Mongo connection.</li>
 * </ul>
 *
 * <p>Formatting is delegated to {@link SessionExportEmitter}; parsing to
 * {@link VanceExportParser} / {@link ClaudeExportParser}. Import creates a
 * fresh session, reconstructs (VANCE) or synthesises (CLAUDE) its chat
 * process so the session stays continuable, and inserts the transcript
 * with timestamps preserved.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionExchangeService {

    private static final String CHAT_PROCESS_NAME = "chat";
    private static final String DEFAULT_ENGINE = "arthur";

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final MemoryService memoryService;
    private final LlmTraceService llmTraceService;
    private final MarvinNodeService marvinNodeService;
    private final PrakRunService prakRunService;

    /**
     * Own Jackson-3 mapper for the anus / file path. The Brain endpoint
     * passes its Spring-managed mapper instead (see
     * {@link #write(OutputStream, ObjectMapper, SessionExportEmitter.ExportData)}).
     * Jackson 3 auto-registers the JavaTime module, so {@code Instant}
     * fields serialise as ISO-8601 strings.
     */
    private final ObjectMapper mapper = JsonMapper.builder().build();

    // ─── Export ───────────────────────────────────────────────────────

    /**
     * Hydrate the full export graph for {@code session}: every process in
     * the session plus everything keyed by those process ids. Each
     * per-process service call is bounded — even a long-running session
     * is at most a few processes.
     */
    public SessionExportEmitter.ExportData collectExport(SessionDocument session) {
        String tenant = session.getTenantId();
        String sessionId = session.getSessionId();
        List<ThinkProcessDocument> processes = thinkProcessService.findBySession(tenant, sessionId);

        List<ChatMessageDocument> chat = new ArrayList<>();
        List<MemoryDocument> memory = new ArrayList<>();
        List<LlmTraceDocument> traces = new ArrayList<>();
        List<MarvinNodeDocument> marvinNodes = new ArrayList<>();
        List<PrakRunRecord> prakRuns = new ArrayList<>();
        for (ThinkProcessDocument p : processes) {
            String pid = p.getId();
            if (pid == null) continue;
            chat.addAll(chatMessageService.history(tenant, sessionId, pid));
            memory.addAll(memoryService.listByProcess(tenant, pid));
            // listByProcess is paginated (cap 200/page); walk pages until
            // exhausted so we don't silently truncate a chatty session.
            int page = 0;
            while (true) {
                Page<LlmTraceDocument> chunk = llmTraceService.listByProcess(tenant, pid, page, 200);
                traces.addAll(chunk.getContent());
                if (chunk.getNumber() + 1 >= chunk.getTotalPages() || chunk.isEmpty()) break;
                page++;
            }
            if ("marvin".equalsIgnoreCase(p.getThinkEngine())) {
                marvinNodes.addAll(marvinNodeService.listAll(pid));
            }
            prakRuns.addAll(prakRunService.listByProcess(tenant, pid, PrakRunService.MAX_LIST_LIMIT));
        }

        return new SessionExportEmitter.ExportData(
                session, processes, chat, memory, traces, marvinNodes, prakRuns);
    }

    /**
     * Hydrate and stream a session export to {@code out} using this
     * service's own mapper. Convenience for the file / anus path; the
     * stream is not closed.
     */
    public void writeExport(OutputStream out, SessionDocument session) throws IOException {
        SessionExportEmitter.write(out, mapper, collectExport(session));
    }

    /**
     * Format an already-hydrated export with a caller-supplied mapper.
     * The Brain endpoint uses this so {@code Instant} serialisation
     * matches the rest of its REST surface.
     */
    public void write(OutputStream out, ObjectMapper callerMapper,
            SessionExportEmitter.ExportData data) throws IOException {
        SessionExportEmitter.write(out, callerMapper, data);
    }

    // ─── Import ───────────────────────────────────────────────────────

    /**
     * Parse {@code in} (VANCE or CLAUDE export) and materialise it as a
     * fresh, continuable session under the request's tenant/project/user.
     * The input stream is fully read but not closed.
     */
    public ImportResult importSession(InputStream in, ImportRequest req) throws IOException {
        List<JsonNode> rows = readRows(in);
        ImportFormat fmt = req.format() == ImportFormat.AUTO ? detectFormat(rows) : req.format();
        ParsedImport parsed = switch (fmt) {
            case VANCE -> VanceExportParser.parse(mapper, rows);
            case CLAUDE -> ClaudeExportParser.parse(mapper, rows);
            case AUTO -> throw new IllegalStateException("format resolved to AUTO");
        };

        // 1) Fresh session.
        String displayName = firstNonBlank(req.displayName(), parsed.displayName());
        String profile = firstNonBlankOr(parsed.profile(), Profiles.WEB);
        SessionDocument session = sessionService.create(
                req.tenantId(), req.userId(), req.projectId(), displayName,
                profile, "", "import", false);
        String sessionId = session.getSessionId();

        String title = firstNonBlank(req.title(), parsed.title());
        if (title != null) {
            sessionService.patchMetadata(sessionId,
                    SessionMetadataPatchRequest.builder().title(title).build());
        }

        // 2) Chat process — reconstruct (VANCE) or synthesise (CLAUDE),
        //    so ensureChatProcess reuses it on connect and history replays.
        ChatProcessSpec spec = parsed.chatProcess() != null ? parsed.chatProcess() : synthSpec(req);
        ThinkProcessDocument proc = thinkProcessService.create(
                req.tenantId(), req.projectId(), sessionId, CHAT_PROCESS_NAME,
                spec.engine(), spec.thinkEngineVersion(), null, null, null,
                spec.engineParams(), spec.recipeName(), spec.promptOverride(),
                spec.promptOverrideAppend(), spec.promptMode(), null,
                spec.allowedToolsOverride(), null,
                spec.skillNames().isEmpty() ? null : spec.skillNames(), null);
        String chatProcId = proc.getId();
        sessionService.setChatProcessId(sessionId, chatProcId);
        sessionService.markBootstrapped(sessionId);

        // 3) Messages — timestamps + order preserved by insertCopies.
        Map<String, String> msgIdMap = insertTurns(req.tenantId(), sessionId, chatProcId, parsed.turns());

        // 4) Memories (VANCE only) + rebind sourceRefs / supersede / archived links.
        Map<String, String> memIdMap = insertMemories(
                req.tenantId(), req.projectId(), sessionId, chatProcId,
                parsed.memories(), msgIdMap);
        rebindArchived(parsed.turns(), msgIdMap, memIdMap);
        int memoryCount = parsed.memories().size();

        // 5) Optional: seed one ARCHIVED_CHAT memory with the whole transcript.
        if (req.asMemory() && !parsed.turns().isEmpty()) {
            seedTranscriptMemory(req.tenantId(), req.projectId(), sessionId, chatProcId, parsed.turns());
            memoryCount++;
        }

        // 6) Denormalised chat preview (insertCopies is deliberately silent).
        seedPreview(sessionId, parsed.turns());

        log.info("Imported {} session sessionId='{}' tenant='{}' project='{}' messages={} memories={}",
                fmt, sessionId, req.tenantId(), req.projectId(), parsed.turns().size(), memoryCount);
        return new ImportResult(sessionId, parsed.turns().size(), memoryCount, fmt.name(), title);
    }

    private ChatProcessSpec synthSpec(ImportRequest req) {
        String engine = firstNonBlankOr(req.engine(), DEFAULT_ENGINE);
        return new ChatProcessSpec(engine, null, req.recipe(), new LinkedHashMap<>(),
                null, null, PromptMode.APPEND, null, List.of());
    }

    private Map<String, String> insertTurns(String tenant, String sessionId,
            String chatProcId, List<ImportedTurn> turns) {
        if (turns.isEmpty()) return Map.of();
        List<ChatMessageDocument> copies = new ArrayList<>(turns.size());
        for (ImportedTurn t : turns) {
            copies.add(ChatMessageDocument.builder()
                    .tenantId(tenant)
                    .sessionId(sessionId)
                    .thinkProcessId(chatProcId)
                    .role(t.role())
                    .content(t.content())
                    .thinking(t.thinking())
                    .archivedInMemoryId(null)  // rebound after memories exist
                    .tags(new LinkedHashSet<>(t.tags()))
                    .meta(new LinkedHashMap<>(t.meta()))
                    .createdAt(t.at())
                    .build());
        }
        List<ChatMessageDocument> saved = chatMessageService.insertCopies(copies);
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < turns.size(); i++) {
            String oldId = turns.get(i).sourceId();
            String newId = saved.get(i).getId();
            if (oldId != null && newId != null) map.put(oldId, newId);
        }
        return map;
    }

    private Map<String, String> insertMemories(String tenant, String projectId, String sessionId,
            String chatProcId, List<ImportedMemory> memories, Map<String, String> msgIdMap) {
        if (memories.isEmpty()) return Map.of();
        List<MemoryDocument> copies = new ArrayList<>(memories.size());
        for (ImportedMemory m : memories) {
            List<String> refs = new ArrayList<>();
            for (String ref : m.sourceRefIds()) {
                String mapped = msgIdMap.get(ref);
                if (mapped != null) refs.add(mapped);
            }
            copies.add(MemoryDocument.builder()
                    .tenantId(tenant)
                    .projectId(projectId)
                    .sessionId(sessionId)
                    .thinkProcessId(chatProcId)
                    .kind(m.kind())
                    .title(m.title())
                    .content(m.content())
                    .sourceRefs(refs)
                    .metadata(new LinkedHashMap<>(m.metadata()))
                    .supersededByMemoryId(null)
                    .createdAt(m.at())
                    .build());
        }
        List<MemoryDocument> saved = memoryService.insertCopies(copies);

        // old→new memory id map for rebinding supersede + archived links.
        Map<String, String> memIdMap = new LinkedHashMap<>();
        for (int i = 0; i < memories.size(); i++) {
            String oldId = memories.get(i).sourceId();
            String newId = saved.get(i).getId();
            if (oldId != null && newId != null) memIdMap.put(oldId, newId);
        }

        // supersede chains
        for (int i = 0; i < memories.size(); i++) {
            String supBy = memories.get(i).supersededBySourceId();
            String newTarget = supBy == null ? null : memIdMap.get(supBy);
            String newSelf = saved.get(i).getId();
            if (newTarget != null && newSelf != null) {
                memoryService.supersede(newSelf, newTarget);
            }
        }
        return memIdMap;
    }

    /**
     * Re-point {@code archivedInMemoryId} on the imported messages to the
     * imported memories (grouped per memory = one {@code markArchived}
     * each), so compacted turns stay archived and aren't double-replayed
     * alongside their summary memory.
     */
    private void rebindArchived(List<ImportedTurn> turns,
            Map<String, String> msgIdMap, Map<String, String> memIdMap) {
        if (memIdMap.isEmpty()) return;
        Map<String, List<String>> byMemory = new LinkedHashMap<>();
        for (ImportedTurn t : turns) {
            String oldMem = t.archivedInMemorySourceId();
            if (oldMem == null) continue;
            String newMem = memIdMap.get(oldMem);
            String newMsg = t.sourceId() == null ? null : msgIdMap.get(t.sourceId());
            if (newMem != null && newMsg != null) {
                byMemory.computeIfAbsent(newMem, k -> new ArrayList<>()).add(newMsg);
            }
        }
        for (Map.Entry<String, List<String>> e : byMemory.entrySet()) {
            chatMessageService.markArchived(e.getValue(), e.getKey());
        }
    }

    private void seedTranscriptMemory(String tenant, String projectId, String sessionId,
            String chatProcId, List<ImportedTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (ImportedTurn t : turns) {
            sb.append(t.role().name()).append(": ").append(t.content()).append("\n\n");
        }
        memoryService.save(MemoryDocument.builder()
                .tenantId(tenant)
                .projectId(projectId)
                .sessionId(sessionId)
                .thinkProcessId(chatProcId)
                .kind(MemoryKind.ARCHIVED_CHAT)
                .title("Imported transcript")
                .content(sb.toString().strip())
                .build());
    }

    private void seedPreview(String sessionId, List<ImportedTurn> turns) {
        if (turns.isEmpty()) return;
        // First USER turn seeds firstUserMessage (write-once in the service).
        for (ImportedTurn t : turns) {
            if (t.role() == ChatRole.USER) {
                sessionService.touchChatPreview(sessionId, t.role().name(), t.content(), t.at());
                break;
            }
        }
        // Last turn sets the last-message preview.
        ImportedTurn last = turns.get(turns.size() - 1);
        sessionService.touchChatPreview(sessionId, last.role().name(), last.content(), last.at());
    }

    // ─── parse helpers ────────────────────────────────────────────────

    private List<JsonNode> readRows(InputStream in) throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    rows.add(mapper.readTree(line));
                } catch (Exception e) {
                    // Skip a malformed line rather than failing the whole import.
                    log.trace("skipping unparseable import line: {}", e.toString());
                }
            }
        }
        return rows;
    }

    private ImportFormat detectFormat(List<JsonNode> rows) {
        boolean sawClaude = false;
        for (JsonNode row : rows) {
            JsonNode t = row.get("type");
            if (t == null || t.isNull()) continue;
            String type = t.asString();
            if ("session_meta".equals(type)) return ImportFormat.VANCE;
            if ("ai-title".equals(type) || "user".equals(type) || "assistant".equals(type)) {
                sawClaude = true;
            }
        }
        if (sawClaude) return ImportFormat.CLAUDE;
        throw new IllegalArgumentException(
                "Could not detect session format (no session_meta / user / assistant rows)");
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static String firstNonBlankOr(@Nullable String a, String fallback) {
        return (a != null && !a.isBlank()) ? a : fallback;
    }
}
