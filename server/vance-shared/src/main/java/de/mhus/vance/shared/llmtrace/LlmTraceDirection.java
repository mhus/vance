package de.mhus.vance.shared.llmtrace;

/**
 * What kind of LLM-related event a {@link LlmTraceDocument} represents.
 * Mirrors the natural roles of a chat-completion roundtrip — input
 * messages, the produced reply, plus tool-call request/result pairs
 * when the engine ran the multi-step tool loop.
 */
public enum LlmTraceDirection {
    /** A message sent to the LLM (system / user / prior assistant). */
    INPUT,
    /** The LLM's reply text. */
    OUTPUT,
    /** A tool invocation request the LLM emitted in its reply. */
    TOOL_CALL,
    /** The result of a tool invocation, fed back into the next LLM call. */
    TOOL_RESULT
}
