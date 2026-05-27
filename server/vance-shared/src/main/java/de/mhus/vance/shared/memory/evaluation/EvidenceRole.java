package de.mhus.vance.shared.memory.evaluation;

/**
 * Speaker role of the chat turn that an {@link Evidence} points at.
 *
 * <p>Used by downstream consumers to filter out items whose evidence
 * is purely model self-narration or general-knowledge restate — see
 * {@code planning/memory-evaluation-pipeline.md} §3 "User-vs-Allgemeinwissen-Filter".
 */
public enum EvidenceRole {

    /** Turn was emitted by the user. Primary source of truth. */
    USER,

    /** Turn was emitted by the assistant. Relevant when it carries
     *  synthesis, codebase observation, or user-confirmed decisions. */
    ASSISTANT,

    /** Turn was a tool result. Relevant for stable observations only. */
    TOOL
}
