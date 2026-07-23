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
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private final ClientEventPublisher publisher = mock(ClientEventPublisher.class);
    private final ProcessEventEmitter eventEmitter = mock(ProcessEventEmitter.class);
    // jackson 3 ObjectMapper. Real instance — convertValue path is the
    // production path we want to exercise.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final EddieChatFrameHandler handler = new EddieChatFrameHandler(
            triage, thinkProcessService, objectMapper, pool, publisher, eventEmitter);

    @Test
    void assistantMessage_runsTriage_andPersistsSummary_andForwardsVerbatim() {
        WorkerLinkSnapshot link = link("w-1");
        when(pool.findEddieIdForWorker("w-1")).thenReturn(Optional.of("eddie-1"));
        when(thinkProcessService.findById("eddie-1")).thenReturn(Optional.of(eddie("eddie-1")));

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

        // Deterministic VERBATIM forward to Eddie's session.
        ArgumentCaptor<ChatMessageAppendedData> forwarded =
                ArgumentCaptor.forClass(ChatMessageAppendedData.class);
        verify(publisher).publish(eq("eddie-sess"),
                eq(MessageType.CHAT_MESSAGE_APPENDED), forwarded.capture());
        assertThat(forwarded.getValue().getThinkProcessId()).isEqualTo("eddie-1");
        assertThat(forwarded.getValue().getProcessName()).isEqualTo("eddie");
        assertThat(forwarded.getValue().getContent()).isEqualTo("Tests sind grün.");

        // VERBATIM is the auto-forward branch — Eddie must NOT think
        // again on top of it, otherwise she'd reformulate text the
        // user already heard.
        verify(eventEmitter, never()).scheduleTurn(any());
        verify(thinkProcessService, never()).appendPending(any(), any(), any());
    }

    @Test
    void nonVerbatimAssistantMessage_wakesEddieLane() {
        // Voice-mode mid-length plain prose → REFORMULATE; Eddie has
        // to decide RELAY/RELAY_INBOX. The engine-bind PROCESS_EVENT
        // detour is suppressed (parent watches via Working WS), so the
        // chat frame handler is the only path that wakes her lane.
        WorkerLinkSnapshot link = link("w-1");
        when(pool.findEddieIdForWorker("w-1")).thenReturn(Optional.of("eddie-1"));
        when(thinkProcessService.findById("eddie-1")).thenReturn(Optional.of(eddie("eddie-1")));
        when(thinkProcessService.appendPending(eq("eddie-1"), any(), eq("w-1")))
                .thenReturn(true);

        String midLength = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(4);
        handler.onChatFrame(envelope(MessageType.CHAT_MESSAGE_APPENDED,
                ChatMessageAppendedData.builder()
                        .role(ChatRole.ASSISTANT)
                        .content(midLength)
                        .build()),
                link);

        // No verbatim forward — non-VERBATIM branch.
        verify(publisher, never())
                .publish(eq("eddie-sess"), eq(MessageType.CHAT_MESSAGE_APPENDED), any());

        // The pending PROCESS_EVENT must carry the worker's source-id
        // so Eddie's prompt block can resolve <process-event sourceProcessId="...">.
        ArgumentCaptor<PendingMessageDocument> pending =
                ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(thinkProcessService).appendPending(eq("eddie-1"), pending.capture(), eq("w-1"));
        assertThat(pending.getValue().getType()).isEqualTo(PendingMessageType.PROCESS_EVENT);
        assertThat(pending.getValue().getSourceProcessId()).isEqualTo("w-1");
        // The event must carry the FULL worker reply, not the ~120-char
        // triage summary — RELAY_INBOX posts it verbatim to the inbox
        // (code-review Phase 2).
        assertThat(pending.getValue().getContent()).isEqualTo(midLength);
        assertThat(pending.getValue().getContent().length()).isGreaterThan(120);

        verify(eventEmitter).scheduleTurn("eddie-1");
    }

    @Test
    void midLengthAssistantMessage_doesNotForward() {
        // Voice-mode mid-length plain prose → REFORMULATE; no auto-forward.
        WorkerLinkSnapshot link = link("w-1");
        when(pool.findEddieIdForWorker("w-1")).thenReturn(Optional.of("eddie-1"));
        when(thinkProcessService.findById("eddie-1")).thenReturn(Optional.of(eddie("eddie-1")));
        when(thinkProcessService.appendPending(eq("eddie-1"), any(), eq("w-1")))
                .thenReturn(true);

        String midLength = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(4);
        handler.onChatFrame(envelope(MessageType.CHAT_MESSAGE_APPENDED,
                ChatMessageAppendedData.builder()
                        .role(ChatRole.ASSISTANT)
                        .content(midLength)
                        .build()),
                link);

        // Snapshot is updated, but no forward (the LLM reformulates next turn).
        verify(thinkProcessService).upsertWorkerLink(eq("eddie-1"), eq(link));
        verify(publisher, never())
                .publish(eq("eddie-sess"), eq(MessageType.CHAT_MESSAGE_APPENDED), any());
    }

    @Test
    void unresolvedEddieOwner_doesNotWakeAnyLane() {
        // The wake-up is also gated on the pool reverse-lookup —
        // without an Eddie owner, there is no lane to schedule.
        WorkerLinkSnapshot link = link("w-orphan");
        when(pool.findEddieIdForWorker("w-orphan")).thenReturn(Optional.empty());

        String midLength = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(4);
        handler.onChatFrame(envelope(MessageType.CHAT_MESSAGE_APPENDED,
                ChatMessageAppendedData.builder()
                        .role(ChatRole.ASSISTANT)
                        .content(midLength)
                        .build()),
                link);

        verify(thinkProcessService, never()).appendPending(any(), any(), any());
        verify(eventEmitter, never()).scheduleTurn(any());
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

    private static ThinkProcessDocument eddie(String id) {
        return ThinkProcessDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId("_user_mike")
                .sessionId("eddie-sess")
                .name("eddie")
                .build();
    }

    private static WebSocketEnvelope envelope(String type, Object data) {
        WebSocketEnvelope env = WebSocketEnvelope.notification(type, data);
        return env;
    }
}
