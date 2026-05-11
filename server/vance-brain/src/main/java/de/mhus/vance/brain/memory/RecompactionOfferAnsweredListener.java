package de.mhus.vance.brain.memory;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.shared.inbox.InboxItemAnsweredEvent;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@code RECOMPACTION_OFFER} inbox answers by calling
 * {@link MemoryCompactionService#compactRange} when the user accepts.
 *
 * <p>Trigger: {@link InboxItemAnsweredEvent} where the item carries the
 * {@link RecompactionTags#TAG_INBOX_OFFER} tag. Acceptance is the
 * {@code APPROVAL}-shape pair
 * {@code outcome=DECIDED, value={"approved": true}}. Anything else —
 * INSUFFICIENT_INFO / UNDECIDABLE / explicit refusal — is a no-op; the
 * inbox row stays answered for audit, no chat-history mutation happens.
 *
 * <p>Distinct from {@link de.mhus.vance.brain.inbox.InboxAnsweredListener}
 * which routes generic answers back to the originating engine via the
 * pending-message queue. Recompaction-offers don't ride that channel —
 * they act on the chat history directly, no engine round-trip needed.
 *
 * <p>See {@code planning/topic-recompaction.md} §5.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecompactionOfferAnsweredListener {

    private final MemoryCompactionService compactionService;
    private final ThinkProcessService thinkProcessService;
    private final MetricService metricService;

    @EventListener
    public void onAnswered(InboxItemAnsweredEvent event) {
        InboxItemDocument item = event.item();
        List<String> tags = item.getTags();
        if (tags == null || !tags.contains(RecompactionTags.TAG_INBOX_OFFER)) {
            return;
        }
        AnswerPayload answer = item.getAnswer();
        if (answer == null || answer.getOutcome() != AnswerOutcome.DECIDED) {
            log.info("RecompactionOffer item='{}' answered without DECIDED outcome ({}); no-op",
                    item.getId(),
                    answer == null ? "null" : answer.getOutcome());
            metricService.counter("vance.recompaction.offer", "outcome", "undecided").increment();
            return;
        }
        if (!isApproved(answer.getValue())) {
            log.info("RecompactionOffer item='{}' answered DECIDED but not approved; no-op",
                    item.getId());
            metricService.counter("vance.recompaction.offer", "outcome", "rejected").increment();
            return;
        }

        String processId = item.getOriginProcessId();
        if (processId == null || processId.isBlank()) {
            log.warn("RecompactionOffer item='{}' missing originProcessId — cannot recompact",
                    item.getId());
            return;
        }
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(processId);
        if (processOpt.isEmpty()) {
            log.warn("RecompactionOffer item='{}' origin process='{}' gone — skipping",
                    item.getId(), processId);
            return;
        }
        ThinkProcessDocument process = processOpt.get();

        Map<String, Object> payload = item.getPayload();
        if (payload == null) {
            log.warn("RecompactionOffer item='{}' has no payload — cannot resolve range",
                    item.getId());
            return;
        }
        Instant from = parseInstant(payload.get(RecompactionTags.PAYLOAD_RANGE_START_AT));
        Instant to = parseInstant(payload.get(RecompactionTags.PAYLOAD_RANGE_END_AT));
        String topicLabel = asString(payload.get(RecompactionTags.PAYLOAD_TOPIC_LABEL),
                "plan-" + processId);
        if (from == null) {
            log.warn("RecompactionOffer item='{}' payload missing rangeStartAt — cannot recompact",
                    item.getId());
            return;
        }

        CompactionResult result = compactionService.compactRange(
                process, from, to, topicLabel);
        if (result.compacted()) {
            log.info("RecompactionOffer item='{}' applied — process='{}' topic='{}' "
                            + "messagesCompacted={} memoryId='{}'",
                    item.getId(), processId, topicLabel,
                    result.messagesCompacted(), result.memoryId());
            metricService.counter("vance.recompaction.offer", "outcome", "accepted").increment();
        } else {
            log.info("RecompactionOffer item='{}' compactRange noop — process='{}' reason='{}'",
                    item.getId(), processId, result.reason());
            metricService.counter("vance.recompaction.offer", "outcome", "accepted_noop").increment();
        }
    }

    private static boolean isApproved(Map<String, Object> value) {
        if (value == null) return false;
        Object approved = value.get("approved");
        return approved instanceof Boolean b && b;
    }

    private static Instant parseInstant(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String asString(Object raw, String fallback) {
        return raw instanceof String s && !s.isBlank() ? s : fallback;
    }
}
