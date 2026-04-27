package de.mhus.vance.api.zaphod;

/**
 * Multi-head orchestration patterns. V1 implements only
 * {@link #COUNCIL}; the rest are documented for forward-compatibility
 * (see {@code specification/zaphod-engine.md} §2).
 */
public enum ZaphodPattern {
    /**
     * All heads receive the same question. After every head replied,
     * a synthesizer LLM-call combines their answers into one
     * (consensus / disagreement / recommendation).
     */
    COUNCIL,

    /** Multi-round opposition (V2). */
    DEBATE,

    /** Generator + Critic feedback loop (V2). */
    GENERATOR_CRITIC,

    /** Self-consistency: N parallel attempts + judge (V2). */
    BRANCH_AND_VOTE
}
