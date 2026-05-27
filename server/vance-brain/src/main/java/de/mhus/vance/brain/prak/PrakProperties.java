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
    private int periodicTriggerTurns = 25;

    /**
     * Periodic Prak trigger: absolute floor on the token-budget trigger.
     * Used directly when {@link #periodicTriggerTokenFraction} is zero
     * (no model-aware scaling) <em>or</em> as the minimum when the
     * fraction-of-context-window yields a smaller number. Catches single
     * huge turns (code pastes, long tool results). {@code 0} disables.
     */
    private int periodicTriggerTokenBudget = 20000;

    /**
     * Periodic Prak trigger: fraction of the outer engine's
     * <em>context window</em> (from {@code ai-models.yaml}) at which
     * the token-budget trigger fires. Default {@code 0.05} → on
     * Sonnet 200k the budget is 10k tokens; on a 16k model it would
     * be 800 tokens but the absolute floor
     * {@link #periodicTriggerTokenBudget} dominates there.
     *
     * <p>Effective budget = {@code max(periodicTriggerTokenBudget,
     * contextWindow * periodicTriggerTokenFraction)} when the model
     * is known, otherwise just {@link #periodicTriggerTokenBudget}.
     *
     * <p>{@code 0.0} disables the fraction-based scaling — the
     * trigger then uses the absolute budget regardless of model.
     */
    private double periodicTriggerTokenFraction = 0.05;

    /**
     * Compaction trigger thresholds — fractions of the model's
     * context window. Picked by {@code CompactionTriggerService} to
     * select a {@code CompactionMode}:
     *
     * <ul>
     *   <li>est-tokens / context-window ≥ emergency → EMERGENCY</li>
     *   <li>≥ hard → HARD</li>
     *   <li>≥ soft → SOFT</li>
     *   <li>otherwise → NONE</li>
     * </ul>
     *
     * <p>Defaults: SOFT at 40% (compaction starts early, gently —
     * the periodic Prak runs in front of it have rated most messages
     * by then), HARD at 85% (context getting tight, drop normal
     * too), EMERGENCY at 95% (last resort, keep only pinned + last 3).
     */
    private double compactionSoftThreshold = 0.40;
    private double compactionHardThreshold = 0.85;
    private double compactionEmergencyThreshold = 0.95;

    /**
     * Anchor sizes per mode — the {@code last K} messages always stay
     * verbatim regardless of strength. The smaller the anchor, the
     * deeper the compaction reaches.
     */
    private int softAnchor = 10;
    private int hardAnchor = 5;
    private int emergencyAnchor = 3;

    /**
     * When {@code true}, {@code MemoryCompactionService} runs Prak
     * ad-hoc over any messages that are still unrated <em>before</em>
     * the strength-aware selection picks who to compact. Pays the
     * extra Prak-call latency (a few seconds) in exchange for guaranteed
     * Strength-tagged input to the selector. Default {@code false} —
     * the optimistic fallback (TrivialPatterns + age heuristic for
     * unrated) is fast and usually enough.
     */
    private boolean inlineOnCompaction = false;
}
