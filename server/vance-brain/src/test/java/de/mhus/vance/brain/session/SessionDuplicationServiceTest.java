package de.mhus.vance.brain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class SessionDuplicationServiceTest {

    @Mock private SessionService sessionService;
    @Mock private ThinkProcessService thinkProcessService;
    @Mock private ChatMessageService chatMessageService;
    @Mock private MemoryService memoryService;

    private SessionDuplicationService service;

    @BeforeEach
    void setUp() {
        service = new SessionDuplicationService(
                sessionService, thinkProcessService, chatMessageService, memoryService);
    }

    @Test
    void duplicate_remapsProcessSessionAndMemoryIds_acrossChatMemory() {
        // ── source session with a chat process ──
        SessionDocument source = SessionDocument.builder()
                .sessionId("sess_old").tenantId("t1").projectId("p1")
                .userId("u1").chatProcessId("proc_old").build();
        SessionDocument copy = SessionDocument.builder()
                .sessionId("sess_new").tenantId("t1").projectId("p1")
                .userId("u1").title("Copy of X").build();
        when(sessionService.findBySessionId("sess_old")).thenReturn(Optional.of(source));
        when(sessionService.createCopy(source, "Copy of X")).thenReturn(copy);

        // ── chat process copy gets a fresh id ──
        ThinkProcessDocument newProc = ThinkProcessDocument.builder()
                .id("proc_new").sessionId("sess_new").build();
        when(thinkProcessService.duplicateProcessIntoSession("proc_old", "sess_new", "p1"))
                .thenReturn(Optional.of(newProc));

        // ── source history: m1 archived into mem_old, m2/m3 active ──
        ChatMessageDocument m1 = msg("msg1", "proc_old", ChatRole.USER, "hello");
        m1.setArchivedInMemoryId("mem_old");
        ChatMessageDocument m2 = msg("msg2", "proc_old", ChatRole.ASSISTANT, "hi");
        ChatMessageDocument m3 = msg("msg3", "proc_old", ChatRole.USER, "again");
        when(chatMessageService.history("t1", "sess_old", "proc_old"))
                .thenReturn(List.of(m1, m2, m3));
        // insert assigns ids by index (order preserved)
        when(chatMessageService.insertCopies(anyList())).thenAnswer(inv -> {
            List<ChatMessageDocument> in = inv.getArgument(0);
            for (int i = 0; i < in.size(); i++) in.get(i).setId("nmsg" + (i + 1));
            return in;
        });

        // ── one ARCHIVED_CHAT memory on the chat process, referencing msg1 ──
        MemoryDocument mem = MemoryDocument.builder()
                .id("mem_old").tenantId("t1").projectId("p1").sessionId("sess_old")
                .thinkProcessId("proc_old").kind(MemoryKind.ARCHIVED_CHAT)
                .content("summary").sourceRefs(new java.util.ArrayList<>(List.of("msg1")))
                .build();
        when(memoryService.listBySession("t1", "sess_old")).thenReturn(List.of(mem));
        when(memoryService.insertCopies(anyList())).thenAnswer(inv -> {
            List<MemoryDocument> in = inv.getArgument(0);
            for (int i = 0; i < in.size(); i++) in.get(i).setId("nmem" + (i + 1));
            return in;
        });

        SessionDuplicationService.DuplicateResult result =
                service.duplicate("sess_old", "Copy of X");

        assertThat(result.newSessionId()).isEqualTo("sess_new");
        assertThat(result.title()).isEqualTo("Copy of X");

        // chat-process relinked to the copied process
        verify(sessionService).setChatProcessId("sess_new", "proc_new");

        // messages retargeted to the new session + process, archive link cleared pre-rebind
        ArgumentCaptor<List<ChatMessageDocument>> msgCap = ArgumentCaptor.captor();
        verify(chatMessageService).insertCopies(msgCap.capture());
        List<ChatMessageDocument> copiedMsgs = msgCap.getValue();
        assertThat(copiedMsgs).hasSize(3);
        assertThat(copiedMsgs).allSatisfy(m -> {
            assertThat(m.getSessionId()).isEqualTo("sess_new");
            assertThat(m.getThinkProcessId()).isEqualTo("proc_new");
            // archive link is cleared at copy time and re-pointed only after
            // the memory copies exist (verified via markArchived below).
            assertThat(m.getArchivedInMemoryId()).isNull();
        });

        // memory retargeted + sourceRefs remapped to the copied message id
        ArgumentCaptor<List<MemoryDocument>> memCap = ArgumentCaptor.captor();
        verify(memoryService).insertCopies(memCap.capture());
        MemoryDocument copiedMem = memCap.getValue().get(0);
        assertThat(copiedMem.getSessionId()).isEqualTo("sess_new");
        assertThat(copiedMem.getThinkProcessId()).isEqualTo("proc_new");
        assertThat(copiedMem.getProjectId()).isEqualTo("p1");
        assertThat(copiedMem.getSourceRefs()).containsExactly("nmsg1");

        // archivedInMemoryId re-pointed to the copied memory for the copy of msg1
        verify(chatMessageService).markArchived(List.of("nmsg1"), "nmem1");
        // no supersede chain to rebind
        verify(memoryService, never()).supersede(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicate_withoutSourceChatProcess_skipsMemoryCopy() {
        SessionDocument source = SessionDocument.builder()
                .sessionId("sess_old").tenantId("t1").projectId("p1")
                .userId("u1").chatProcessId(null).build();
        SessionDocument copy = SessionDocument.builder()
                .sessionId("sess_new").tenantId("t1").projectId("p1").userId("u1").build();
        when(sessionService.findBySessionId("sess_old")).thenReturn(Optional.of(source));
        when(sessionService.createCopy(source, null)).thenReturn(copy);

        SessionDuplicationService.DuplicateResult result = service.duplicate("sess_old", null);

        assertThat(result.newSessionId()).isEqualTo("sess_new");
        verify(thinkProcessService, never())
                .duplicateProcessIntoSession(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        verify(chatMessageService, never()).insertCopies(anyList());
        verify(memoryService, never()).insertCopies(anyList());
    }

    private static ChatMessageDocument msg(
            String id, String procId, ChatRole role, String content) {
        return ChatMessageDocument.builder()
                .id(id).tenantId("t1").sessionId("sess_old").thinkProcessId(procId)
                .role(role).content(content).build();
    }
}
