package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Periodic Prak trigger — called by engines after each successful
 * turn. Reads the messages added since {@code ThinkProcessDocument.
 * lastPrakAt}; when the accumulated count or token budget crosses the
 * configured threshold, runs the side-channel over those messages and
 * advances the cursor.
 *
 * <p>Decoupled from compaction. With this hook in place, Prak no
 * longer waits for the Compaction trigger — it sees every turn within
 * {@link PrakProperties#getPeriodicTriggerTurns()} of its arrival, so
 * by the time compaction (or the context-assembler filter) wants to
 * use {@code STRENGTH:*} tags they are actually present.
 *
 * <p>Cheap-path-filter is still in front of the analyzer call. A
 * batch of pure-ack turns triggers nothing — the periodic check fires
 * but the cheap-path filter skips the analyzer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrakPeriodicTrigger {

    private final PrakProperties props;
    private final ChatMessageService chatMessageService;
    private final ThinkProcessService thinkProcessService;
    private final PrakSideChannelRunner runner;
    private final MetricService metricService;

    /**
     * Engine hook — call after every successful turn. Idempotent;
     * never throws; respects {@link PrakProperties#isSideChannelEnabled()}
     * (no-op when off).
     *
     * @param process current process; must have non-null id/tenantId
     * @param projectId scoping project for the side-channel call;
     *     {@code null} or empty is allowed (Prak then resolves
     *     project-scope via setting cascades).
     */
    public void maybeFire(ThinkProcessDocument process, @Nullable String projectId) {
        if (process == null || process.getId() == null) return;
        if (!props.isSideChannelEnabled()) return;

        int turnsThreshold = props.getPeriodicTriggerTurns();
        int tokenThreshold = props.getPeriodicTriggerTokenBudget();
        if (turnsThreshold <= 0 && tokenThreshold <= 0) {
            // Both knobs disabled → periodic trigger is off; only
            // compaction-side-channel will fire Prak.
            return;
        }

        try {
            List<ChatMessageDocument> unrated = loadUnrated(process);
            if (unrated.isEmpty()) return;

            int approxTokens = countTokens(unrated);
            boolean countCrosses = turnsThreshold > 0 && unrated.size() >= turnsThreshold;
            boolean tokensCross = tokenThreshold > 0 && approxTokens >= tokenThreshold;
            if (!countCrosses && !tokensCross) {
                return;
            }

            log.debug("Periodic Prak fires for process='{}' unrated={} tokens~{} (countCrosses={}, tokensCross={})",
                    process.getId(), unrated.size(), approxTokens, countCrosses, tokensCross);
            metricService.counter("vance.prak.periodicTrigger",
                    "fire", "true").increment();

            boolean ran = runner.run(process, projectId, unrated, "periodic");
            // Advance cursor to the latest message we just handed in, so
            // the next periodic call only sees the next batch.
            Instant latest = unrated.get(unrated.size() - 1).getCreatedAt();
            if (ran && latest != null) {
                thinkProcessService.updateLastPrakAt(process.getId(), latest);
            }
        } catch (RuntimeException e) {
            log.warn("Periodic Prak failed for process='{}': {}",
                    process.getId(), e.toString());
            metricService.counter("vance.prak.periodicTrigger",
                    "fire", "error").increment();
        }
    }

    /**
     * Active-history messages with {@code createdAt > lastPrakAt}. When
     * the cursor is null, every active message counts as unrated.
     */
    private List<ChatMessageDocument> loadUnrated(ThinkProcessDocument process) {
        List<ChatMessageDocument> active = chatMessageService.activeHistory(
                process.getTenantId(),
                process.getSessionId(),
                process.getId());
        Instant cursor = process.getLastPrakAt();
        if (cursor == null) {
            return active;
        }
        List<ChatMessageDocument> out = new ArrayList<>();
        for (ChatMessageDocument m : active) {
            if (m.getCreatedAt() != null && m.getCreatedAt().isAfter(cursor)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Approximate token count — same whitespace-split heuristic the
     * cheap-path filter uses. Off by ~25% in either direction is fine
     * for a trigger-decision.
     */
    static int countTokens(List<ChatMessageDocument> messages) {
        int total = 0;
        for (ChatMessageDocument m : messages) {
            String content = m.getContent();
            if (StringUtils.isBlank(content)) continue;
            for (String t : content.trim().split("\\s+")) {
                if (!t.isEmpty()) total++;
            }
        }
        return total;
    }
}
