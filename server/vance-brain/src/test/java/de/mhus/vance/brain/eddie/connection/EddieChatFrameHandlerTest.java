package de.mhus.vance.brain.eddie.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.eddie.triage.OutputTriageService;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies {@link EddieChatFrameHandler} only fires on committed
 * assistant messages, runs the heuristic triage, persists the summary
 * back to the worker-link snapshot, and resolves the owning Eddie via
 * the pool reverse-lookup.
 */
class EddieChatFrameHandlerTest {

    // Real triage service — heuristic-only, deterministic; not worth
    // stubbing for these smoke tests.
    private final OutputTriageService triage = new OutputTriageService();
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final EddieWorkerConnectionPool pool = mock(EddieWorkerConnectionPool.class);
    // jackson 3 ObjectMapper. Real instance — convertValue path is the
    // production path we want to exercise.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final EddieChatFrameHandler handler = new EddieChatFrameHandler(
            triage, thinkProcessService, objectMapper, pool);

    @Test
    void assistantMessage_runsTriage_andPersistsSummary() {
        WorkerLinkSnapshot link = link("w-1");
        when(pool.findEddieIdForWorker("w-1")).thenReturn(Optional.of("eddie-1"));

        handler.onChatFrame(envelope(MessageType.CHAT_MESSAGE_APPENDED,
                ChatMessageAppendedData.builder()
                        .chatMessageId("cm-1")
                        .thinkProcessId("w-1")
                        .processName("arthur")
                        .role(ChatRole.ASSISTANT)
                        .content("Tests sind grün.")
                        .build()),
                link);

        // Heuristic on short prose under voice-mode → VERBATIM/LOW with
        // memorySummary set to the first line.
        assertThat(link.getTriageSummary()).isEqualTo("Tests sind grün.");
        assertThat(link.getLastCriticality()).isEqualTo(Criticality.LOW);
        assertThat(link.getLastSeen()).isNotNull();

        verify(thinkProcessService).upsertWorkerLink(eq("eddie-1"), eq(link));
    }

    @Test
    void streamChunkFrame_isIgnored() {
        WorkerLinkSnapshot link = link("w-1");

        handler.onChatFrame(envelope(MessageType.CHAT_MESSAGE_STREAM_CHUNK,
                ChatMessageAppendedData.builder()
                        .role(ChatRole.ASSISTANT)
                        .content("partial...")
                        .build()),
                link);

        assertThat(link.getTriageSummary()).isNull();
        verify(thinkProcessService, never()).upsertWorkerLink(any(), any());
    }

    @Test
    void userRoleFrame_isIgnored_evenIfAppended() {
        WorkerLinkSnapshot link = link("w-1");

        handler.onChatFrame(envelope(MessageType.CHAT_MESSAGE_APPENDED,
                ChatMessageAppendedData.builder()
                        .role(ChatRole.USER)
                        .content("Eddie's own input echoed back")
                        .build()),
                link);

        assertThat(link.getTriageSummary()).isNull();
        verify(thinkProcessService, never()).upsertWorkerLink(any(), any());
    }

    @Test
    void emptyContent_isIgnored() {
        WorkerLinkSnapshot link = link("w-1");

        handler.onChatFrame(envelope(MessageType.CHAT_MESSAGE_APPENDED,
                ChatMessageAppendedData.builder()
                        .role(ChatRole.ASSISTANT)
                        .content("")
                        .build()),
                link);

        assertThat(link.getTriageSummary()).isNull();
        verify(thinkProcessService, never()).upsertWorkerLink(any(), any());
    }

    @Test
    void unresolvedEddieOwner_skipsPersistence_butStillUpdatesInMemory() {
        WorkerLinkSnapshot link = link("w-orphan");
        when(pool.findEddieIdForWorker("w-orphan")).thenReturn(Optional.empty());

        handler.onChatFrame(envelope(MessageType.CHAT_MESSAGE_APPENDED,
                ChatMessageAppendedData.builder()
                        .role(ChatRole.ASSISTANT)
                        .content("plan vorgelegt")
                        .build()),
                link);

        // In-memory snapshot still got the triage summary so a render
        // running on this same JVM picks it up; we just couldn't
        // persist because there's no pool entry tying it to an Eddie.
        assertThat(link.getTriageSummary()).isEqualTo("plan vorgelegt");
        verify(thinkProcessService, never()).upsertWorkerLink(any(), any());
    }

    private static WorkerLinkSnapshot link(String workerProcessId) {
        return WorkerLinkSnapshot.builder()
                .workerProcessId(workerProcessId)
                .workerProcessName("arthur")
                .workerProjectName("auth-refactor")
                .build();
    }

    private static WebSocketEnvelope envelope(String type, Object data) {
        WebSocketEnvelope env = WebSocketEnvelope.notification(type, data);
        return env;
    }
}
