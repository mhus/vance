package de.mhus.vance.brain.memory.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.memeval.*} — tunables for the memory-evaluation
 * pipeline. See {@code planning/memory-evaluation-pipeline.md} §4a /
 * §4c for context.
 */
@Data
@ConfigurationProperties(prefix = "vance.memeval")
public class MemoryEvaluationProperties {

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
}
