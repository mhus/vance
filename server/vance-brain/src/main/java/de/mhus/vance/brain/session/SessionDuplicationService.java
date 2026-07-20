package de.mhus.vance.brain.session;

import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Duplicates a session together with its chat memory — the "Duplicate"
 * action from the Web-UI session list. A duplicate is a fresh, resumable
 * copy the user can continue independently of the source.
 *
 * <p><b>Scope (v1):</b> the session's user-facing metadata, its single
 * {@code chat} think-process (configuration + full message history), and
 * every session/chat-process-scoped {@link MemoryDocument} (compaction
 * summaries, scratchpad, plan, insight). Worker child-processes are
 * <em>not</em> copied — they are transient task executions, not the
 * conversation. The copy stays in the same project/user/tenant; shared
 * project assets (documents, RAG) are referenced, not duplicated.
 * Telemetry (traces, usage, event-log, prak-runs) and the transient
 * pending-message queue are deliberately skipped.
 *
 * <p><b>Cross-collection id remapping.</b> Copies get fresh Mongo ids and
 * a fresh {@code sessionId}. Two id maps thread the references straight:
 * an old→new think-process-id map (chat-message {@code thinkProcessId},
 * session {@code chatProcessId}) and an old→new memory-id map
 * (chat-message {@code archivedInMemoryId}, memory {@code sourceRefs} /
 * {@code supersededByMemoryId}).
 *
 * <p>The copied chat process is left {@code IDLE} and is not started here;
 * it resumes like any reopened session's chat process when the user opens
 * the duplicate. Each owning service performs its own writes (data
 * ownership per CLAUDE.md) — this service only orchestrates and holds the
 * id maps.
 *
 * <p>Not run in a Mongo transaction (mirrors the delete cascade in
 * {@link SessionLifecycleService}): the steps are ordered so a mid-way
 * failure leaves a partially-populated but self-consistent copy rather
 * than corrupting the source.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionDuplicationService {

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final MemoryService memoryService;

    /** Outcome of a duplicate: business id + resolved title of the copy. */
    public record DuplicateResult(String newSessionId, @Nullable String title) {}

    /**
     * Duplicates {@code sessionId}. The caller (controller) has already
     * verified ownership + tenant scope.
     *
     * @param sessionId source session business id
     * @param newTitle  explicit title for the copy, or {@code null} to
     *                  carry over the source title verbatim
     * @throws IllegalArgumentException when the source session is missing
     */
    public DuplicateResult duplicate(String sessionId, @Nullable String newTitle) {
        SessionDocument source = sessionService.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session not found: " + sessionId));

        SessionDocument copy = sessionService.createCopy(source, newTitle);
        String tenantId = source.getTenantId();
        String newSessionId = copy.getSessionId();
        String newProjectId = copy.getProjectId();

        String sourceChatProcId = source.getChatProcessId();
        if (sourceChatProcId == null || sourceChatProcId.isBlank()) {
            log.info("Duplicated session '{}' → '{}' with no chat memory "
                            + "(source never bootstrapped a chat process)",
                    sessionId, newSessionId);
            return new DuplicateResult(newSessionId, copy.getTitle());
        }

        Optional<ThinkProcessDocument> newChatProc =
                thinkProcessService.duplicateProcessIntoSession(
                        sourceChatProcId, newSessionId, newProjectId);
        if (newChatProc.isEmpty() || newChatProc.get().getId() == null) {
            log.warn("Session '{}' chatProcessId='{}' resolves to no process; "
                            + "copy '{}' created without chat memory",
                    sessionId, sourceChatProcId, newSessionId);
            return new DuplicateResult(newSessionId, copy.getTitle());
        }
        String newChatProcId = newChatProc.get().getId();
        sessionService.setChatProcessId(newSessionId, newChatProcId);

        // Read source conversation once; the chat process is the only one
        // in scope, so history + session/process-scoped memory suffice.
        List<ChatMessageDocument> srcMessages =
                chatMessageService.history(tenantId, sessionId, sourceChatProcId);
        List<MemoryDocument> srcMemories = new ArrayList<>();
        for (MemoryDocument m : memoryService.listBySession(tenantId, sessionId)) {
            String pid = m.getThinkProcessId();
            if (pid == null || sourceChatProcId.equals(pid)) {
                srcMemories.add(m);
            }
        }

        Map<String, String> msgIdMap =
                copyMessages(srcMessages, newSessionId, newChatProcId);
        Map<String, String> memIdMap = copyMemories(
                srcMemories, newSessionId, newProjectId, sourceChatProcId,
                newChatProcId, msgIdMap);
        rebindArchivedMemory(srcMessages, msgIdMap, memIdMap);
        rebindSupersedeChains(srcMemories, memIdMap);

        log.info("Duplicated session '{}' → '{}' (messages={}, memories={})",
                sessionId, newSessionId, msgIdMap.size(), memIdMap.size());
        return new DuplicateResult(newSessionId, copy.getTitle());
    }

    /**
     * Copies every chat message of the source chat process into the copy,
     * retargeting {@code sessionId}/{@code thinkProcessId}. {@code
     * archivedInMemoryId} is left null here and re-pointed by
     * {@link #rebindArchivedMemory} once the memory copies exist. Returns
     * an old→new message-id map (input order preserved by the insert).
     */
    private Map<String, String> copyMessages(
            List<ChatMessageDocument> srcMessages,
            String newSessionId, String newChatProcId) {
        if (srcMessages.isEmpty()) return Map.of();
        List<ChatMessageDocument> copies = new ArrayList<>(srcMessages.size());
        for (ChatMessageDocument m : srcMessages) {
            copies.add(ChatMessageDocument.builder()
                    .tenantId(m.getTenantId())
                    .sessionId(newSessionId)
                    .thinkProcessId(newChatProcId)
                    .role(m.getRole())
                    .content(m.getContent())
                    .thinking(m.getThinking())
                    .archivedInMemoryId(null)
                    .tags(new LinkedHashSet<>(m.getTags()))
                    .meta(new LinkedHashMap<>(m.getMeta()))
                    .senderUserId(m.getSenderUserId())
                    .senderDisplayName(m.getSenderDisplayName())
                    .addressedToAgent(m.isAddressedToAgent())
                    .createdAt(m.getCreatedAt())
                    .build());
        }
        List<ChatMessageDocument> saved = chatMessageService.insertCopies(copies);
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < srcMessages.size(); i++) {
            String oldId = srcMessages.get(i).getId();
            String newId = saved.get(i).getId();
            if (oldId != null && newId != null) map.put(oldId, newId);
        }
        return map;
    }

    /**
     * Copies session/chat-process-scoped memories, retargeting scope and
     * remapping {@code sourceRefs} via {@code msgIdMap}. Supersede links
     * are re-established afterwards by {@link #rebindSupersedeChains} once
     * every copy has an id. Returns an old→new memory-id map.
     */
    private Map<String, String> copyMemories(
            List<MemoryDocument> srcMemories,
            String newSessionId, String newProjectId,
            String sourceChatProcId, String newChatProcId,
            Map<String, String> msgIdMap) {
        if (srcMemories.isEmpty()) return Map.of();
        List<MemoryDocument> copies = new ArrayList<>(srcMemories.size());
        for (MemoryDocument m : srcMemories) {
            String newProcId = sourceChatProcId.equals(m.getThinkProcessId())
                    ? newChatProcId : null;
            List<String> refs = new ArrayList<>();
            for (String ref : m.getSourceRefs()) {
                String mapped = msgIdMap.get(ref);
                if (mapped != null) refs.add(mapped);
            }
            copies.add(MemoryDocument.builder()
                    .tenantId(m.getTenantId())
                    .projectId(newProjectId)
                    .sessionId(newSessionId)
                    .thinkProcessId(newProcId)
                    .kind(m.getKind())
                    .title(m.getTitle())
                    .content(m.getContent())
                    .sourceRefs(refs)
                    .metadata(new LinkedHashMap<>(m.getMetadata()))
                    .supersededByMemoryId(null)
                    .createdAt(m.getCreatedAt())
                    .build());
        }
        List<MemoryDocument> saved = memoryService.insertCopies(copies);
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < srcMemories.size(); i++) {
            String oldId = srcMemories.get(i).getId();
            String newId = saved.get(i).getId();
            if (oldId != null && newId != null) map.put(oldId, newId);
        }
        return map;
    }

    /**
     * Re-points {@code archivedInMemoryId} on the copied messages to the
     * copied memories, grouped per target memory so it is one atomic
     * {@code markArchived} per memory. Copies whose source memory was out
     * of scope stay active (unarchived).
     */
    private void rebindArchivedMemory(
            List<ChatMessageDocument> srcMessages,
            Map<String, String> msgIdMap, Map<String, String> memIdMap) {
        Map<String, List<String>> byNewMemory = new LinkedHashMap<>();
        for (ChatMessageDocument m : srcMessages) {
            String oldMem = m.getArchivedInMemoryId();
            if (oldMem == null) continue;
            String newMem = memIdMap.get(oldMem);
            String newMsg = msgIdMap.get(m.getId());
            if (newMem == null || newMsg == null) continue;
            byNewMemory.computeIfAbsent(newMem, k -> new ArrayList<>()).add(newMsg);
        }
        for (Map.Entry<String, List<String>> e : byNewMemory.entrySet()) {
            chatMessageService.markArchived(e.getValue(), e.getKey());
        }
    }

    /**
     * Re-establishes the compaction supersede chain between the copied
     * memories so the {@code activeBy*} queries return the same active
     * summary as in the source.
     */
    private void rebindSupersedeChains(
            List<MemoryDocument> srcMemories, Map<String, String> memIdMap) {
        for (MemoryDocument m : srcMemories) {
            String oldSup = m.getSupersededByMemoryId();
            if (oldSup == null) continue;
            String newSelf = memIdMap.get(m.getId());
            String newSup = memIdMap.get(oldSup);
            if (newSelf == null || newSup == null) continue;
            memoryService.supersede(newSelf, newSup);
        }
    }
}
