package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Results return to the requester: a task a conversation head handed over
 * carries its return address, the loop records it at drain, the report
 * consumes it, and a closing head takes its leftovers along. Every write is
 * one atomic entry on the loop's engineParams — Control writes the
 * attributes of the same process from its own lane.
 */
class TrillianTaskReplyRoutingTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final EngineMessageRouter router = mock(EngineMessageRouter.class);
    private final EngineMessageService engineMessages = mock(EngineMessageService.class);
    private final TrillianInternalApi api = new TrillianInternalApi(
            thinkProcessService,
            router,
            engineMessages,
            mock(de.mhus.vance.brain.thinkengine.ProcessEventEmitter.class),
            mock(ChatMessageService.class),
            mock(de.mhus.vance.brain.scheduling.LaneScheduler.class),
            mock(TrillianNatureRegistry.class));

    @Test
    void aTaskWithAReturnAddress_carriesItInTheRequestPayload() {
        when(router.dispatch(any(), anyString(), any())).thenReturn(true);
        ThinkProcessDocument loop = loopWithTaskReplies(null);

        api.enqueueTask("head-1", loop, "count the docs", "head-1");

        ArgumentCaptor<de.mhus.vance.shared.thinkprocess.PendingMessageDocument> message =
                ArgumentCaptor.forClass(de.mhus.vance.shared.thinkprocess.PendingMessageDocument.class);
        verify(router).dispatch(eq("head-1"), eq("loop-1"), message.capture());
        assertThat(message.getValue().getPayload())
                .containsEntry(TrillianInternalApi.PAYLOAD_KEY_REPLY_TO, "head-1")
                .containsEntry(TrillianInternalApi.PAYLOAD_KEY_DESCRIPTION, "count the docs");
    }

    @Test
    void aControlRaisedTask_carriesNoReturnAddress() {
        when(router.dispatch(any(), anyString(), any())).thenReturn(true);

        api.enqueueTask("control-1", loopWithTaskReplies(null), "count the docs");

        ArgumentCaptor<de.mhus.vance.shared.thinkprocess.PendingMessageDocument> message =
                ArgumentCaptor.forClass(de.mhus.vance.shared.thinkprocess.PendingMessageDocument.class);
        verify(router).dispatch(eq("control-1"), eq("loop-1"), message.capture());
        assertThat(message.getValue().getPayload()).doesNotContainKey(TrillianInternalApi.PAYLOAD_KEY_REPLY_TO);
    }

    @Test
    void anAddressEqualToTheLoopsPeer_isNotCarried() {
        // Control's own tasks report to the peer anyway — an entry for them
        // would only grow the loop's map.
        when(router.dispatch(any(), anyString(), any())).thenReturn(true);
        ThinkProcessDocument loop = loopWithTaskReplies(null);
        loop.getEngineParams().put(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID, "control-1");

        api.enqueueTask("control-1", loop, "count the docs", "control-1");

        ArgumentCaptor<de.mhus.vance.shared.thinkprocess.PendingMessageDocument> message =
                ArgumentCaptor.forClass(de.mhus.vance.shared.thinkprocess.PendingMessageDocument.class);
        verify(router).dispatch(eq("control-1"), eq("loop-1"), message.capture());
        assertThat(message.getValue().getPayload()).doesNotContainKey(TrillianInternalApi.PAYLOAD_KEY_REPLY_TO);
    }

    @Test
    void recordingAddresses_writesOneAtomicEntryPerTask() {
        loopWithTaskReplies(null);

        api.recordReplyTo("loop-1", Map.of("task-1", "head-1"));

        verify(thinkProcessService)
                .setEngineParamEntry("loop-1", TrillianInternalApi.PARAM_TASK_REPLIES, "task-1", "head-1");
        verify(thinkProcessService, org.mockito.Mockito.never()).replaceEngineParams(any(), any());
    }

    @Test
    void peekingTheAddress_leavesItForTheFinalReport() {
        // task_needs_input: the task is not over, its result must still find
        // the head that asked.
        loopWithTaskReplies(Map.of("task-42", "head-1"));

        assertThat(api.peekReplyTo("loop-1", "task-42")).contains("head-1");
        assertThat(api.consumeReplyTo("loop-1", "task-42")).contains("head-1");
    }

    @Test
    void aClosingHead_takesItsLeftoverAddressesAlong() {
        loopWithTaskReplies(Map.of("task-1", "head-1", "task-2", "head-2", "task-3", "head-1"));

        assertThat(api.pruneRepliesTo("loop-1", "head-1")).isEqualTo(2);

        assertThat(api.peekReplyTo("loop-1", "task-1")).isEmpty();
        assertThat(api.peekReplyTo("loop-1", "task-3")).isEmpty();
        assertThat(api.peekReplyTo("loop-1", "task-2")).contains("head-2");
    }

    @Test
    void consumingTheAddress_returnsItAndRemovesTheEntry() {
        ThinkProcessDocument loop = loopWithTaskReplies(Map.of("task-42", "head-1", "task-7", "head-2"));

        assertThat(api.consumeReplyTo("loop-1", "task-42")).contains("head-1");

        // Gone on the second ask — a task is reported once, and the map must
        // not grow with work that finished long ago.
        assertThat(api.consumeReplyTo("loop-1", "task-42")).isEmpty();
        assertThat(api.consumeReplyTo("loop-1", "task-7")).contains("head-2");
    }

    @Test
    void anUnknownTask_hasNoAddress_fallsBackToThePeer() {
        ThinkProcessDocument loop = loopWithTaskReplies(Map.of("task-42", "head-1"));

        assertThat(api.consumeReplyTo("loop-1", "task-99")).isEmpty();
    }

    @Test
    void anAddressOfAClosedHead_fallsBackToThePeer_andIsConsumed() {
        // The head closed before the loop drained its request: the prune ran
        // before the address existed. The report must not go to a dead head.
        loopWithTaskReplies(Map.of("task-42", "head-1"));
        ThinkProcessDocument closed = process("head-1");
        closed.setStatus(ThinkProcessStatus.CLOSED);
        when(thinkProcessService.findById("head-1")).thenReturn(Optional.of(closed));

        assertThat(api.peekReplyTo("loop-1", "task-42")).isEmpty();
        assertThat(api.consumeReplyTo("loop-1", "task-42")).isEmpty();
        verify(thinkProcessService)
                .setEngineParamEntry("loop-1", TrillianInternalApi.PARAM_TASK_REPLIES, "task-42", null);
    }

    @Test
    void aRequestStillInTheLoopsInbox_countsAsPending() {
        // Delivered, not drained yet: the loop works through a backlog. The
        // head is waiting all the same.
        ThinkProcessDocument loop = loopWithTaskReplies(null);
        when(engineMessages.findInboxedByTargets(List.of("loop-1"))).thenReturn(List.of(request("loop-1", "head-1")));

        assertThat(api.hasPendingRepliesFor(loop, "head-1")).isTrue();
        assertThat(api.hasPendingRepliesFor(loop, "head-2")).isFalse();
    }

    @Test
    void aRequestStillInTheHeadsOutbox_countsAsPending() {
        ThinkProcessDocument loop = loopWithTaskReplies(null);
        when(engineMessages.findOutboxedBySenders(List.of("head-1"))).thenReturn(List.of(request("loop-1", "head-1")));

        assertThat(api.hasPendingRepliesFor(loop, "head-1")).isTrue();
    }

    @Test
    void aDrainedRequest_countsAsPending_untilItsReport() {
        ThinkProcessDocument loop = loopWithTaskReplies(Map.of("task-1", "head-1"));

        assertThat(api.hasPendingRepliesFor(loop, "head-1")).isTrue();
    }

    private static EngineMessageDocument request(String target, String replyTo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TrillianInternalApi.PAYLOAD_KEY_TASK_EVENT, TrillianInternalApi.TASK_EVENT_REQUEST);
        payload.put(TrillianInternalApi.PAYLOAD_KEY_REPLY_TO, replyTo);
        EngineMessageDocument m = new EngineMessageDocument();
        m.setTargetProcessId(target);
        m.setPayload(payload);
        return m;
    }

    private static ThinkProcessDocument process(String id) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(id);
        p.setStatus(ThinkProcessStatus.IDLE);
        return p;
    }

    // ──────────────────── concluding, and the worker safety net ────────────────────

    @Test
    void concludingAHeadsTask_reportsToThatHead_andDropsTheWorkerLink() {
        ThinkProcessDocument loop = loopWithTaskReplies(Map.of("task-42", "head-1"));
        givenPeer(loop);
        api.recordTaskWorker("loop-1", "worker-1", "task-42");
        when(router.dispatch(any(), anyString(), any())).thenReturn(true);

        assertThat(api.concludeTask("loop-1", "task-42", true, "42 documents")).isPresent();

        verify(router).dispatch(eq("loop-1"), eq("head-1"), any());
        assertThat(api.taskOfWorker(loop, "worker-1")).isEmpty();
        assertThat(api.peekReplyTo("loop-1", "task-42")).isEmpty();
    }

    @Test
    void concludingControlsTask_reportsToThePeer() {
        // No return address: the task came from the human's Control —
        // the safety net works without any exposure or head.
        ThinkProcessDocument loop = loopWithTaskReplies(null);
        givenPeer(loop);
        when(router.dispatch(any(), anyString(), any())).thenReturn(true);

        assertThat(api.concludeTask("loop-1", "task-7", false, "the worker failed"))
                .isPresent();

        verify(router).dispatch(eq("loop-1"), eq("control-1"), any());
    }

    @Test
    void aRecordedWorker_namesItsTask() {
        ThinkProcessDocument loop = loopWithTaskReplies(null);

        api.recordTaskWorker("loop-1", "worker-1", "task-42");

        assertThat(api.taskOfWorker(loop, "worker-1")).contains("task-42");
        assertThat(api.taskOfWorker(loop, "worker-2")).isEmpty();
    }

    private void givenPeer(ThinkProcessDocument loop) {
        loop.getEngineParams().put(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID, "control-1");
        when(thinkProcessService.findById("control-1")).thenReturn(Optional.of(process("control-1")));
    }

    private ThinkProcessDocument loopWithTaskReplies(Map<String, String> taskReplies) {
        // The heads a report may go to are alive unless a test says otherwise.
        when(thinkProcessService.findById("head-1")).thenReturn(Optional.of(process("head-1")));
        when(thinkProcessService.findById("head-2")).thenReturn(Optional.of(process("head-2")));
        ThinkProcessDocument loop = new ThinkProcessDocument();
        loop.setId("loop-1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (taskReplies != null) {
            params.put(TrillianInternalApi.PARAM_TASK_REPLIES, new LinkedHashMap<>(taskReplies));
        }
        loop.setEngineParams(params);
        when(thinkProcessService.findById("loop-1")).thenReturn(Optional.of(loop));
        // The entry write lands in Mongo; the in-memory loop stands in for the
        // next read, so the mocked write has to mutate it.
        when(thinkProcessService.setEngineParamEntry(eq("loop-1"), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    String param = inv.getArgument(1);
                    String key = inv.getArgument(2);
                    Object value = inv.getArgument(3);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entries = (Map<String, Object>)
                            loop.getEngineParams().computeIfAbsent(param, k -> new LinkedHashMap<String, Object>());
                    if (value == null) {
                        entries.remove(key);
                    } else {
                        entries.put(key, value);
                    }
                    return true;
                });
        return loop;
    }
}
