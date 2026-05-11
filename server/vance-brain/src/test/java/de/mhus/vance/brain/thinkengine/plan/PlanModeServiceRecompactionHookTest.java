package de.mhus.vance.brain.thinkengine.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.memory.RecompactionTags;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the plan-completion → recompaction-offer hook:
 *
 * <ul>
 *   <li>fires when all todos are COMPLETED and ≥ 2 pre-plan USER turns
 *       exist;</li>
 *   <li>does NOT fire when any todo is still pending;</li>
 *   <li>does NOT fire without enough pre-plan history (the plan WAS the
 *       conversation, folding it would empty the chat);</li>
 *   <li>does NOT fire when no {@code MODE:plan} marker exists at all.</li>
 * </ul>
 *
 * <p>See {@code planning/topic-recompaction.md} §4 + §7.
 */
class PlanModeServiceRecompactionHookTest {

    private ThinkProcessService thinkProcessService;
    private PlanModeEventEmitter eventEmitter;
    private ChatMessageService chatMessageService;
    private InboxItemService inboxItemService;
    private PlanModeService service;

    private final Instant planStart = Instant.parse("2026-05-11T14:00:00Z");

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        eventEmitter = mock(PlanModeEventEmitter.class);
        chatMessageService = mock(ChatMessageService.class);
        inboxItemService = mock(InboxItemService.class);
        MetricService metricService = new MetricService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        service = new PlanModeService(
                thinkProcessService, eventEmitter,
                chatMessageService, inboxItemService, metricService);

        // Default: enough pre-plan USER turns. Individual tests override.
        when(chatMessageService.findActiveInRange(
                eq("t"), eq("p-1"), eq(null), any(Instant.class)))
                .thenReturn(List.of(
                        msg(ChatRole.USER, planStart.minusSeconds(120)),
                        msg(ChatRole.ASSISTANT, planStart.minusSeconds(100)),
                        msg(ChatRole.USER, planStart.minusSeconds(60))));
        when(chatMessageService.findLatestCreatedAtForTag(
                eq("t"), eq(Set.of("p-1")), eq("MODE:plan")))
                .thenReturn(Optional.of(planStart));

        // create() returns the persisted doc with an id — we don't pin the
        // value, only that getId() doesn't NPE on the log line in the hook.
        when(inboxItemService.create(any(InboxItemDocument.class)))
                .thenAnswer(inv -> {
                    InboxItemDocument arg = inv.getArgument(0);
                    arg.setId("inbox-99");
                    return arg;
                });
    }

    private ThinkProcessDocument processWithTodos(TodoStatus... statuses) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("p-1");
        p.setTenantId("t");
        p.setSessionId("s");
        p.setTodos(java.util.stream.IntStream.range(0, statuses.length)
                .mapToObj(i -> TodoItem.builder()
                        .id("todo-" + i).content("c" + i).status(statuses[i]).build())
                .toList());
        return p;
    }

    private ChatMessageDocument msg(ChatRole role, Instant at) {
        ChatMessageDocument m = ChatMessageDocument.builder()
                .role(role).content("x").build();
        m.setCreatedAt(at);
        return m;
    }

    @Test
    void allCompleted_withSufficientPrePlan_postsOffer() {
        service.maybeOfferRecompaction(processWithTodos(
                TodoStatus.COMPLETED, TodoStatus.COMPLETED, TodoStatus.COMPLETED));

        ArgumentCaptor<InboxItemDocument> cap =
                ArgumentCaptor.forClass(InboxItemDocument.class);
        verify(inboxItemService).create(cap.capture());
        InboxItemDocument offer = cap.getValue();

        assertThat(offer.getType()).isEqualTo(InboxItemType.APPROVAL);
        assertThat(offer.getCriticality()).isEqualTo(Criticality.NORMAL);
        assertThat(offer.isRequiresAction()).isTrue();
        assertThat(offer.getTags()).containsExactly(RecompactionTags.TAG_INBOX_OFFER);
        assertThat(offer.getOriginProcessId()).isEqualTo("p-1");
        assertThat(offer.getOriginSessionId()).isEqualTo("s");
        assertThat(offer.getTenantId()).isEqualTo("t");
        assertThat(offer.getTitle()).contains("Plan abgeschlossen");

        // Payload carries the range coordinates the listener needs back.
        assertThat(offer.getPayload())
                .containsEntry(RecompactionTags.PAYLOAD_RANGE_START_AT,
                        planStart.toString())
                .containsEntry(RecompactionTags.PAYLOAD_TODO_COUNT, 3)
                .containsKey(RecompactionTags.PAYLOAD_RANGE_END_AT)
                .containsEntry(RecompactionTags.PAYLOAD_TOPIC_LABEL,
                        "plan-p-1-" + planStart.toEpochMilli());
    }

    @Test
    void anyTodoNotCompleted_doesNotPostOffer() {
        service.maybeOfferRecompaction(processWithTodos(
                TodoStatus.COMPLETED, TodoStatus.IN_PROGRESS));

        verify(inboxItemService, never()).create(any());
        // Pre-plan history isn't even consulted — short-circuit on the
        // todo check, not on the history shape.
        verify(chatMessageService, never()).findLatestCreatedAtForTag(
                anyString(), any(), anyString());
    }

    @Test
    void insufficientPrePlanHistory_doesNotPostOffer() {
        // Only one USER turn before planStart — below the threshold of 2.
        when(chatMessageService.findActiveInRange(
                eq("t"), eq("p-1"), eq(null), any(Instant.class)))
                .thenReturn(List.of(
                        msg(ChatRole.USER, planStart.minusSeconds(60))));

        service.maybeOfferRecompaction(processWithTodos(
                TodoStatus.COMPLETED, TodoStatus.COMPLETED));

        verify(inboxItemService, never()).create(any());
    }

    @Test
    void noPlanStartMarker_doesNotPostOffer() {
        when(chatMessageService.findLatestCreatedAtForTag(
                anyString(), any(), eq("MODE:plan")))
                .thenReturn(Optional.empty());

        service.maybeOfferRecompaction(processWithTodos(
                TodoStatus.COMPLETED, TodoStatus.COMPLETED));

        verify(inboxItemService, never()).create(any());
        // Pre-plan history isn't fetched without a plan-start to anchor on.
        verify(chatMessageService, never()).findActiveInRange(
                anyString(), anyString(), any(), any());
    }

    @Test
    void emptyTodos_doesNotPostOffer() {
        // edge: hook is called but todos list is empty (defensive — the
        // wrapper at the call-site already short-circuits, but the
        // helper has to be safe by itself).
        ThinkProcessDocument empty = new ThinkProcessDocument();
        empty.setId("p-1"); empty.setTenantId("t"); empty.setSessionId("s");

        service.maybeOfferRecompaction(empty);

        verify(inboxItemService, never()).create(any());
    }
}
