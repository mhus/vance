package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.shared.inbox.InboxItemAnsweredEvent;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the recompaction-offer answer handler:
 *
 * <ul>
 *   <li>fires {@code compactRange} on {@code DECIDED + approved=true};</li>
 *   <li>no-ops on rejection, INSUFFICIENT_INFO, missing answer, missing tag;</li>
 *   <li>reads range coordinates back out of the payload correctly.</li>
 * </ul>
 *
 * <p>See {@code planning/topic-recompaction.md} §5.
 */
class RecompactionOfferAnsweredListenerTest {

    private MemoryCompactionService compactionService;
    private ThinkProcessService thinkProcessService;
    private RecompactionOfferAnsweredListener listener;

    private final Instant rangeStart = Instant.parse("2026-05-11T14:00:00Z");
    private final Instant rangeEnd = Instant.parse("2026-05-11T14:30:00Z");

    @BeforeEach
    void setUp() {
        compactionService = mock(MemoryCompactionService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        MetricService metricService = new MetricService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        listener = new RecompactionOfferAnsweredListener(
                compactionService, thinkProcessService, metricService);

        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("p-1");
        p.setTenantId("t");
        when(thinkProcessService.findById("p-1")).thenReturn(Optional.of(p));
        when(compactionService.compactRange(any(), any(), any(), anyString()))
                .thenReturn(CompactionResult.success(3, 100, "mem-99", null));
    }

    private InboxItemDocument offer(
            List<String> tags,
            AnswerPayload answer,
            Map<String, Object> payload) {
        InboxItemDocument item = InboxItemDocument.builder()
                .id("inbox-1")
                .tenantId("t")
                .originProcessId("p-1")
                .tags(tags == null ? new ArrayList<>() : new ArrayList<>(tags))
                .payload(payload == null ? new LinkedHashMap<>() : payload)
                .build();
        item.setAnswer(answer);
        return item;
    }

    private Map<String, Object> validPayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(RecompactionTags.PAYLOAD_RANGE_START_AT, rangeStart.toString());
        p.put(RecompactionTags.PAYLOAD_RANGE_END_AT, rangeEnd.toString());
        p.put(RecompactionTags.PAYLOAD_TOPIC_LABEL, "plan-p-1-1234");
        return p;
    }

    private AnswerPayload approved(boolean approved) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("approved", approved);
        return AnswerPayload.builder()
                .outcome(AnswerOutcome.DECIDED)
                .value(value).build();
    }

    // ─── Positive ───────────────────────────────────────────────────────

    @Test
    void approved_callsCompactRangeWithDecodedRangeAndTopic() {
        InboxItemDocument item = offer(
                List.of(RecompactionTags.TAG_INBOX_OFFER),
                approved(true), validPayload());

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        ArgumentCaptor<ThinkProcessDocument> processCap =
                ArgumentCaptor.forClass(ThinkProcessDocument.class);
        ArgumentCaptor<Instant> fromCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
        verify(compactionService).compactRange(
                processCap.capture(), fromCap.capture(),
                toCap.capture(), topicCap.capture());
        assertThat(processCap.getValue().getId()).isEqualTo("p-1");
        assertThat(fromCap.getValue()).isEqualTo(rangeStart);
        assertThat(toCap.getValue()).isEqualTo(rangeEnd);
        assertThat(topicCap.getValue()).isEqualTo("plan-p-1-1234");
    }

    // ─── Negative ───────────────────────────────────────────────────────

    @Test
    void missingTag_skipsEntirely() {
        InboxItemDocument item = offer(
                List.of(), // no RECOMPACTION_OFFER tag
                approved(true), validPayload());

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        verifyNoInteractions(compactionService, thinkProcessService);
    }

    @Test
    void approvedFalse_doesNotCompact() {
        InboxItemDocument item = offer(
                List.of(RecompactionTags.TAG_INBOX_OFFER),
                approved(false), validPayload());

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        verify(compactionService, never()).compactRange(any(), any(), any(), anyString());
    }

    @Test
    void outcomeNotDecided_doesNotCompact() {
        AnswerPayload nope = AnswerPayload.builder()
                .outcome(AnswerOutcome.INSUFFICIENT_INFO)
                .reason("user unsure").build();
        InboxItemDocument item = offer(
                List.of(RecompactionTags.TAG_INBOX_OFFER),
                nope, validPayload());

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        verify(compactionService, never()).compactRange(any(), any(), any(), anyString());
    }

    @Test
    void missingAnswer_doesNotCompact() {
        InboxItemDocument item = offer(
                List.of(RecompactionTags.TAG_INBOX_OFFER),
                null, validPayload());

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        verify(compactionService, never()).compactRange(any(), any(), any(), anyString());
    }

    @Test
    void missingRangeStartInPayload_skips() {
        Map<String, Object> p = validPayload();
        p.remove(RecompactionTags.PAYLOAD_RANGE_START_AT);
        InboxItemDocument item = offer(
                List.of(RecompactionTags.TAG_INBOX_OFFER),
                approved(true), p);

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        verify(compactionService, never()).compactRange(any(), any(), any(), anyString());
    }

    @Test
    void processGone_skips() {
        when(thinkProcessService.findById("p-1")).thenReturn(Optional.empty());
        InboxItemDocument item = offer(
                List.of(RecompactionTags.TAG_INBOX_OFFER),
                approved(true), validPayload());

        listener.onAnswered(new InboxItemAnsweredEvent(item));

        verify(compactionService, never()).compactRange(any(), any(), any(), anyString());
    }
}
