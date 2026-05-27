package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.Evidence;
import de.mhus.vance.shared.prak.ExtractedItem;
import de.mhus.vance.shared.prak.LongTermMemoryAction;
import de.mhus.vance.shared.prak.SpanStrength;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Derives a per-message {@link SpanStrength} from a sanitised
 * {@link EvaluationOutput} plus the original span. Implements §4b of
 * {@code planning/memory-evaluation-pipeline.md} — the LLM does NOT
 * emit strength overrides because 1:N message-ID mappings drift; the
 * deriver computes them deterministically from items + markers + static
 * patterns.
 *
 * <p>Rules (applied in this order; the strongest verdict wins):
 * <ol>
 *   <li><b>Hot-Path marker on the message itself</b> ⇒ {@link
 *       SpanStrength#STRONG}.</li>
 *   <li><b>Evidence of an item with {@code importance >= 4}</b>
 *       ⇒ {@link SpanStrength#STRONG}.</li>
 *   <li><b>Evidence of any other item (not flagged trivial)</b> stays
 *       {@link SpanStrength#NORMAL} (no boost, no downgrade).</li>
 *   <li><b>Context-anchor coupling</b>: a message directly preceding
 *       an evidence message stays {@link SpanStrength#NORMAL} even if
 *       it would otherwise look trivial — the next turn depends on
 *       its setup.</li>
 *   <li><b>Trivial pattern</b> (short ack / assistant self-narration)
 *       AND no other claim ⇒ {@link SpanStrength#WEAK}.</li>
 * </ol>
 *
 * <p>{@link SpanStrength#NORMAL} is the implicit default — only
 * {@link SpanStrength#WEAK} and {@link SpanStrength#STRONG} are
 * recorded in the {@link StrengthDerivation}. The {@link #persist}
 * method then clears any prior {@code STRENGTH:*} tag on every message
 * in the span and writes the override tag for the listed messages.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpanStrengthDeriver {

    private final HotPathMarkerDetector markerDetector;
    private final ChatMessageService chatMessageService;

    /**
     * Pure compute — no Mongo access. Returns the sparse override map.
     * Safe to call with a {@link EvaluationOutput#empty empty} output;
     * the result will then contain only WEAK overrides from trivial
     * patterns (and STRONG from marker detection).
     */
    public StrengthDerivation derive(
            List<SpanMessage> messages, EvaluationOutput evaluation) {
        if (messages == null || messages.isEmpty()) {
            return StrengthDerivation.empty();
        }

        Set<String> highImportanceEvidence = new HashSet<>();
        Set<String> anyItemEvidence = new HashSet<>();
        for (ExtractedItem item : evaluation.items()) {
            boolean high = item.importance() >= ExtractedItem.IMPORTANCE_HIGH
                    || item.longTermMemory().action() == LongTermMemoryAction.PROMOTE;
            for (Evidence e : item.evidence()) {
                anyItemEvidence.add(e.turnId());
                if (high) {
                    highImportanceEvidence.add(e.turnId());
                }
            }
        }

        // Pre-compute hot-path marker hits + "precedes-evidence" anchor set
        // by walking the span once in order.
        Set<String> markerIds = new HashSet<>();
        Set<String> precedesEvidence = new HashSet<>();
        String prevId = null;
        for (SpanMessage msg : messages) {
            String id = msg.messageId();
            if (id != null && markerDetector.hasMarker(msg.content())) {
                markerIds.add(id);
            }
            if (prevId != null && id != null && anyItemEvidence.contains(id)) {
                precedesEvidence.add(prevId);
            }
            prevId = id;
        }

        Map<String, SpanStrength> overrides = new HashMap<>();
        for (SpanMessage msg : messages) {
            String id = msg.messageId();
            if (id == null) continue;

            // 1+2: STRONG candidates
            if (markerIds.contains(id) || highImportanceEvidence.contains(id)) {
                overrides.put(id, SpanStrength.STRONG);
                continue;
            }

            // 3+4: evidence or anchor → NORMAL (no override needed)
            if (anyItemEvidence.contains(id) || precedesEvidence.contains(id)) {
                continue;
            }

            // 5: trivial pattern → WEAK
            String content = msg.content() == null ? "" : msg.content();
            if (TrivialPatterns.isAck(content) || TrivialPatterns.isSelfNarration(content)) {
                overrides.put(id, SpanStrength.WEAK);
            }
        }

        return new StrengthDerivation(Map.copyOf(overrides));
    }

    /**
     * Persist the derivation as {@code STRENGTH:*} tags on the messages
     * via {@link ChatMessageService}. Always clears any prior
     * {@code STRENGTH:*} tag on every span message first (so a previous
     * STRONG that became NORMAL on re-derivation is removed), then
     * writes the override tags grouped by strength.
     *
     * @return total number of {@code ChatMessageDocument}s whose tag
     *     set was modified by the tag-write operations (the clear step
     *     also counts in modified rows when prior tags existed).
     */
    public long persist(List<SpanMessage> messages, StrengthDerivation derivation) {
        if (messages == null || messages.isEmpty()) return 0;
        if (derivation == null) return 0;

        List<String> allIds = messages.stream()
                .map(SpanMessage::messageId)
                .filter(Objects::nonNull)
                .toList();
        if (allIds.isEmpty()) return 0;

        long modified = 0;
        modified += chatMessageService.removeTagsWithPrefix(
                allIds, SpanStrength.TAG_PREFIX);

        // Group overrides by strength to minimise update-round-trips.
        Map<SpanStrength, Set<String>> byStrength = new HashMap<>();
        for (Map.Entry<String, SpanStrength> e : derivation.overrides().entrySet()) {
            byStrength.computeIfAbsent(e.getValue(), k -> new HashSet<>())
                    .add(e.getKey());
        }
        for (Map.Entry<SpanStrength, Set<String>> e : byStrength.entrySet()) {
            modified += chatMessageService.tagAll(e.getValue(), e.getKey().tag());
        }
        log.debug("SpanStrengthDeriver persist span={} weak={} strong={} modified={}",
                allIds.size(),
                count(byStrength, SpanStrength.WEAK),
                count(byStrength, SpanStrength.STRONG),
                modified);
        return modified;
    }

    private static int count(Map<SpanStrength, Set<String>> by, SpanStrength s) {
        Set<String> v = by.get(s);
        return v == null ? 0 : v.size();
    }
}
