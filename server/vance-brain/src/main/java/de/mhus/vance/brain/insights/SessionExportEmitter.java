package de.mhus.vance.brain.insights;

import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.llmtrace.LlmTraceDocument;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.prak.audit.PrakRunRecord;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Writes a single session — and every Mongo record that belongs to it —
 * as one JSON object per line ({@code application/x-ndjson}).
 *
 * <p>Layout:
 * <ol>
 *   <li>One {@code session_meta} record (header).</li>
 *   <li>Every other record sorted by its timestamp ascending. Types:
 *       {@code process}, {@code message}, {@code memory},
 *       {@code llm_trace}, {@code marvin_node}, {@code prak_run}.</li>
 * </ol>
 *
 * <p>Records carry a {@code type} discriminator and an {@code at}
 * ISO-8601 timestamp so external tools (jq, pandas) can group / filter
 * without schema knowledge.
 *
 * <p>This emitter is intentionally a pure function over {@link ExportData}
 * so the controller stays thin and the assembly is unit-testable
 * without a Spring context.
 */
final class SessionExportEmitter {

    private SessionExportEmitter() {}

    /**
     * Pre-loaded, tenant-scoped slice of the session graph. The
     * controller hydrates this from its services; the emitter only
     * formats it.
     */
    record ExportData(
            SessionDocument session,
            List<ThinkProcessDocument> processes,
            List<ChatMessageDocument> chatMessages,
            List<MemoryDocument> memories,
            List<LlmTraceDocument> llmTraces,
            List<MarvinNodeDocument> marvinNodes,
            List<PrakRunRecord> prakRuns) {}

    /**
     * Stream the export to {@code out}. Does not close the stream — the
     * caller (Spring's {@code StreamingResponseBody}) owns the response
     * body lifecycle. {@code mapper} controls JSON formatting; we use
     * the controller's shared {@link ObjectMapper} so {@code Instant}
     * serialisation matches the rest of the REST surface.
     */
    static void write(OutputStream out, ObjectMapper mapper, ExportData data) throws IOException {
        // session_meta is always first — gives the consumer the
        // session id, project, user and engines used before any
        // per-record context.
        writeLine(out, mapper, sessionMeta(data));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ThinkProcessDocument p : data.processes()) rows.add(processRecord(p));
        for (ChatMessageDocument m : data.chatMessages()) rows.add(messageRecord(m));
        for (MemoryDocument m : data.memories()) rows.add(memoryRecord(m));
        for (LlmTraceDocument t : data.llmTraces()) rows.add(llmTraceRecord(t));
        for (MarvinNodeDocument n : data.marvinNodes()) rows.add(marvinNodeRecord(n));
        for (PrakRunRecord r : data.prakRuns()) rows.add(prakRunRecord(r));

        // Sort by the per-record `at` timestamp. Records with a null
        // timestamp sort to the end so they're easy to spot at the
        // bottom rather than getting mixed in at epoch.
        rows.sort(Comparator
                .comparing((Map<String, Object> row) -> (Instant) row.get("at"),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> (String) row.get("type")));

        for (Map<String, Object> row : rows) {
            writeLine(out, mapper, row);
        }
    }

    private static void writeLine(OutputStream out, ObjectMapper mapper, Map<String, Object> row)
            throws IOException {
        out.write(mapper.writeValueAsBytes(row));
        out.write('\n');
    }

    // ─── Per-type record builders ─────────────────────────────────────

    private static Map<String, Object> sessionMeta(ExportData data) {
        SessionDocument s = data.session();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "session_meta");
        r.put("at", s.getCreatedAt());
        putIfNotNull(r, "id", s.getId());
        r.put("sessionId", s.getSessionId());
        r.put("tenantId", s.getTenantId());
        r.put("userId", s.getUserId());
        r.put("projectId", s.getProjectId());
        putIfNotNull(r, "displayName", s.getDisplayName());
        putIfNotNull(r, "title", s.getTitle());
        r.put("profile", s.getProfile() == null ? "" : s.getProfile());
        putIfNotNull(r, "clientVersion", s.getClientVersion());
        putIfNotNull(r, "clientName", s.getClientName());
        r.put("status", s.getStatus() == null ? "" : s.getStatus().name());
        putIfNotNull(r, "chatProcessId", s.getChatProcessId());
        putIfNotNull(r, "createdAt", s.getCreatedAt());
        putIfNotNull(r, "lastActivityAt", s.getLastActivityAt());
        putIfNotNull(r, "lastMessageAt", s.getLastMessageAt());
        putIfNotNull(r, "lastMessageRole", s.getLastMessageRole());
        putIfNotNull(r, "firstUserMessage", s.getFirstUserMessage());
        r.put("processCount", data.processes().size());
        r.put("messageCount", data.chatMessages().size());
        r.put("memoryCount", data.memories().size());
        r.put("llmTraceCount", data.llmTraces().size());
        r.put("marvinNodeCount", data.marvinNodes().size());
        r.put("prakRunCount", data.prakRuns().size());
        r.put("exportedAt", Instant.now());
        return r;
    }

    private static Map<String, Object> processRecord(ThinkProcessDocument p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "process");
        r.put("at", p.getCreatedAt());
        putIfNotNull(r, "id", p.getId());
        r.put("processId", p.getId() == null ? "" : p.getId());
        r.put("sessionId", p.getSessionId());
        r.put("projectId", p.getProjectId());
        r.put("name", p.getName());
        putIfNotNull(r, "title", p.getTitle());
        r.put("thinkEngine", p.getThinkEngine());
        putIfNotNull(r, "thinkEngineVersion", p.getThinkEngineVersion());
        putIfNotNull(r, "recipeName", p.getRecipeName());
        putIfNotNull(r, "goal", p.getGoal());
        r.put("status", p.getStatus() == null ? "" : p.getStatus().name());
        r.put("mode", p.getMode() == null ? "" : p.getMode().name());
        putIfNotNull(r, "parentProcessId", p.getParentProcessId());
        r.put("engineParams", new LinkedHashMap<>(p.getEngineParams()));
        putIfNotNull(r, "promptOverride", p.getPromptOverride());
        putIfNotNull(r, "promptOverrideAppend", p.getPromptOverrideAppend());
        r.put("promptMode", p.getPromptMode() == null ? "" : p.getPromptMode().name());
        r.put("activeSkills", p.getActiveSkills().stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", a.getName());
                    putIfNotNull(m, "resolvedFromScope",
                            a.getResolvedFromScope() == null ? null : a.getResolvedFromScope().name());
                    m.put("oneShot", a.isOneShot());
                    m.put("fromRecipe", a.isFromRecipe());
                    putIfNotNull(m, "activatedAt", a.getActivatedAt());
                    return m;
                })
                .toList());
        putIfNotNull(r, "closeReason", p.getCloseReason() == null ? null : p.getCloseReason().name());
        putIfNotNull(r, "haltRequested", p.isHaltRequested() ? Boolean.TRUE : null);
        putIfNotNull(r, "updatedAt", p.getUpdatedAt());
        return r;
    }

    private static Map<String, Object> messageRecord(ChatMessageDocument c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "message");
        r.put("at", c.getCreatedAt());
        putIfNotNull(r, "id", c.getId());
        r.put("processId", c.getThinkProcessId());
        r.put("sessionId", c.getSessionId());
        r.put("role", c.getRole() == null ? "" : c.getRole().name());
        r.put("content", c.getContent());
        if (c.getTags() != null && !c.getTags().isEmpty()) {
            r.put("tags", new ArrayList<>(c.getTags()));
        }
        putIfNotNull(r, "archivedInMemoryId", c.getArchivedInMemoryId());
        if (c.getMeta() != null && !c.getMeta().isEmpty()) {
            r.put("meta", new LinkedHashMap<>(c.getMeta()));
        }
        return r;
    }

    private static Map<String, Object> memoryRecord(MemoryDocument m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "memory");
        r.put("at", m.getCreatedAt());
        putIfNotNull(r, "id", m.getId());
        putIfNotNull(r, "processId", m.getThinkProcessId());
        putIfNotNull(r, "sessionId", m.getSessionId());
        r.put("kind", m.getKind() == null ? "" : m.getKind().name());
        putIfNotNull(r, "title", m.getTitle());
        r.put("content", m.getContent());
        if (m.getSourceRefs() != null && !m.getSourceRefs().isEmpty()) {
            r.put("sourceRefs", new ArrayList<>(m.getSourceRefs()));
        }
        if (m.getMetadata() != null && !m.getMetadata().isEmpty()) {
            r.put("metadata", new LinkedHashMap<>(m.getMetadata()));
        }
        putIfNotNull(r, "supersededByMemoryId", m.getSupersededByMemoryId());
        putIfNotNull(r, "supersededAt", m.getSupersededAt());
        return r;
    }

    private static Map<String, Object> llmTraceRecord(LlmTraceDocument t) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "llm_trace");
        r.put("at", t.getCreatedAt());
        putIfNotNull(r, "id", t.getId());
        r.put("processId", t.getProcessId());
        putIfNotNull(r, "sessionId", t.getSessionId());
        putIfNotNull(r, "engine", t.getEngine());
        putIfNotNull(r, "turnId", t.getTurnId());
        r.put("sequence", t.getSequence());
        r.put("direction", t.getDirection() == null ? "" : t.getDirection().name());
        putIfNotNull(r, "role", t.getRole());
        putIfNotNull(r, "content", t.getContent());
        putIfNotNull(r, "toolName", t.getToolName());
        putIfNotNull(r, "toolCallId", t.getToolCallId());
        putIfNotNull(r, "modelAlias", t.getModelAlias());
        putIfNotNull(r, "providerModel", t.getProviderModel());
        putIfNotNull(r, "tokensIn", t.getTokensIn());
        putIfNotNull(r, "tokensOut", t.getTokensOut());
        putIfNotNull(r, "cacheCreationInputTokens", t.getCacheCreationInputTokens());
        putIfNotNull(r, "cacheReadInputTokens", t.getCacheReadInputTokens());
        putIfNotNull(r, "elapsedMs", t.getElapsedMs());
        return r;
    }

    private static Map<String, Object> marvinNodeRecord(MarvinNodeDocument n) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "marvin_node");
        r.put("at", n.getCreatedAt());
        putIfNotNull(r, "id", n.getId());
        r.put("processId", n.getProcessId());
        putIfNotNull(r, "parentId", n.getParentId());
        r.put("position", n.getPosition());
        r.put("goal", n.getGoal());
        r.put("taskKind", n.getTaskKind() == null ? "" : n.getTaskKind().name());
        r.put("status", n.getStatus() == null ? "" : n.getStatus().name());
        if (n.getTaskSpec() != null && !n.getTaskSpec().isEmpty()) {
            r.put("taskSpec", new LinkedHashMap<>(n.getTaskSpec()));
        }
        if (n.getArtifacts() != null && !n.getArtifacts().isEmpty()) {
            r.put("artifacts", new LinkedHashMap<>(n.getArtifacts()));
        }
        putIfNotNull(r, "failureReason", n.getFailureReason());
        putIfNotNull(r, "spawnedProcessId", n.getSpawnedProcessId());
        putIfNotNull(r, "inboxItemId", n.getInboxItemId());
        putIfNotNull(r, "currentPhase",
                n.getCurrentPhase() == null ? null : n.getCurrentPhase().name());
        putIfNotNull(r, "candidateResult", n.getCandidateResult());
        if (n.getCalledSubProcessIds() != null && !n.getCalledSubProcessIds().isEmpty()) {
            r.put("calledSubProcessIds", new ArrayList<>(n.getCalledSubProcessIds()));
        }
        putIfNotNull(r, "startedAt", n.getStartedAt());
        putIfNotNull(r, "completedAt", n.getCompletedAt());
        return r;
    }

    private static Map<String, Object> prakRunRecord(PrakRunRecord pr) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "prak_run");
        r.put("at", pr.getCreatedAt());
        putIfNotNull(r, "id", pr.getId());
        putIfNotNull(r, "processId", pr.getProcessId());
        putIfNotNull(r, "sessionId", pr.getSessionId());
        r.put("runId", pr.getRunId());
        r.put("trigger", pr.getTrigger());
        putIfNotNull(r, "windowFromTurnId", pr.getWindowFromTurnId());
        putIfNotNull(r, "windowToTurnId", pr.getWindowToTurnId());
        r.put("windowMessages", pr.getWindowMessages());
        r.put("rawItemCount", pr.getRawItemCount());
        r.put("finalItemCount", pr.getFinalItemCount());
        r.put("droppedNoEvidence", pr.getDroppedNoEvidence());
        r.put("droppedLowConfidence", pr.getDroppedLowConfidence());
        r.put("droppedBySupersedeWithinBatch", pr.getDroppedBySupersedeWithinBatch());
        r.put("duplicatesMerged", pr.getDuplicatesMerged());
        r.put("confidencePenalised", pr.getConfidencePenalised());
        r.put("hardCapTriggered", pr.isHardCapTriggered());
        r.put("evidenceCoverage", pr.getEvidenceCoverage());
        r.put("lowCoverage", pr.isLowCoverage());
        r.put("promoted", pr.getPromoted());
        r.put("inboxOffered", pr.getInboxOffered());
        r.put("skipped", pr.getSkipped());
        r.put("refreshed", pr.getRefreshed());
        if (pr.getPersistedMemoryIds() != null && !pr.getPersistedMemoryIds().isEmpty()) {
            r.put("persistedMemoryIds", new ArrayList<>(pr.getPersistedMemoryIds()));
        }
        putIfNotNull(r, "model", pr.getModel());
        r.put("durationMs", pr.getDurationMs());
        return r;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) map.put(key, value);
    }
}
