package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.prak.SpanStrength;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.prak.*} — tunables for the memory-evaluation
 * pipeline. See {@code planning/memory-evaluation-pipeline.md} §4a /
 * §4c for context.
 */
@Data
@ConfigurationProperties(prefix = "vance.prak")
public class PrakProperties {

    /**
     * Minimum acceptable confidence for an analyzer-produced item.
     * Items below this are dropped before persistence — "the model
     * was guessing". §4c.5.
     */
    private double confidenceFloor = 0.6;

    /**
     * Multiplier applied to the upper end of the expected item range
     * to derive the hard cap. Items beyond this count get their
     * promotion action downgraded to {@code inboxOffer}. §4c.2.
     */
    private double hardCapMultiplier = 2.0;

    /**
     * Floor on the hard cap regardless of expected range — never
     * downgrade below this absolute count. §4c.2.
     */
    private int hardCapAbsoluteFloor = 15;

    /**
     * Multiplier on existing-item confidence after partial evidence
     * halluzination. {@code 0.7} mirrors the §4c.3 spec.
     */
    private double partialEvidenceConfidencePenalty = 0.7;

    /**
     * Minimum label-overlap fraction for the dedup pre-filter.
     * Items below this similarity are never considered duplicates.
     * §4c.6.
     */
    private double dedupMinLabelOverlap = 0.8;

    /**
     * Minimum token-Jaccard similarity on item content for dedup.
     * Combined (AND) with label-overlap. §4c.6.
     *
     * <p>Tokens are word-split, lowercased; comparison is set Jaccard
     * (|A ∩ B| / |A ∪ B|). This is sharper than edit-distance for short
     * natural-language statements where shared boilerplate ("User
     * prefers …") would otherwise dominate the score.
     */
    private double dedupMinContentSimilarity = 0.8;

    /**
     * Minimum substantial-message count under which the coverage
     * check is suppressed (not meaningful on tiny windows). §4c.4.
     */
    private int coverageMinWindowSize = 10;

    /**
     * Lower bound on evidence coverage that triggers a low-coverage
     * metric event. Below this we suspect the analyzer ignored
     * substantial parts of the span. §4c.4.
     */
    private double coverageLowThreshold = 0.2;

    /**
     * Master switch for the compaction-side-channel — when {@code true},
     * {@code MemoryCompactionService} runs the {@code PrakService}
     * over the same span it summarises, then hands the sanitized output
     * to downstream consumers (strength derivation, memory promotion).
     *
     * <p>Defaults to {@code false} until phases 6+7 of §12 are wired in —
     * running the analyzer without a consumer just burns tokens. Flip to
     * {@code true} per tenant once promotion + strength tagging are live.
     */
    private boolean sideChannelEnabled = false;

    /**
     * When {@code true}, {@link HistoryStrengthFilter} drops chat messages
     * tagged below {@link #contextFilterMinStrength} from the LLM-replay
     * history. The persisted {@code ChatMessageDocument} is unchanged —
     * only the working-buffer projection handed to the engine call
     * shrinks. §6.1.
     *
     * <p>Defaults to {@code false}: even with strength tags present we
     * keep the full replay until the tenant explicitly enables the
     * filter (decoupled from {@link #sideChannelEnabled} so the two can
     * be toggled independently).
     */
    private boolean contextFilterEnabled = false;

    /**
     * Minimum strength a message must carry to survive
     * {@link HistoryStrengthFilter}. Anything <em>below</em> this
     * threshold is dropped. Default {@link SpanStrength#NORMAL} drops
     * only {@link SpanStrength#WEAK} messages — the conservative
     * starting point.
     */
    private SpanStrength contextFilterMinStrength = SpanStrength.NORMAL;

    /**
     * Periodic Prak trigger: fire when at least this many unrated
     * messages have accumulated since the last successful pass.
     * {@code 0} disables the count-based trigger; the token-budget
     * trigger ({@link #periodicTriggerTokenBudget}) still applies.
     */
    private int periodicTriggerTurns = 5;

    /**
     * Periodic Prak trigger: fire when the unrated messages since the
     * last successful pass sum to at least this many approximate
     * tokens, even if {@link #periodicTriggerTurns} is not yet reached.
     * Catches single huge turns (code pastes, long tool results).
     * {@code 0} disables the token-budget trigger.
     */
    private int periodicTriggerTokenBudget = 5000;
}
