package de.mhus.vance.brain.thinkengine.loop;

/**
 * A safety net ended the turn: why, a one-line technical detail, and the
 * best free text the model produced so far (partial progress, possibly
 * blank). The stop texts are never blank — the reply is often the caller's
 * only signal about how the turn ended.
 */
public record SafetyStop(SafetyStopReason reason, String detail, String partialText) {

    /** Why a safety net ended the turn. */
    public enum SafetyStopReason {
        /** The same tool batch (name + arguments) repeated N times in a row. */
        IDLE_STUCK,
        /** The model returned neither text nor a tool call, even after retries. */
        EMPTY_REPLY,
        /** The provider call failed (stream error, retry budget exhausted). */
        LLM_FAILURE,
        /** The per-turn wallclock net elapsed. */
        WALLCLOCK,
        /** The recipe's opt-in {@code maxIterations} cap was reached. */
        ITERATION_LIMIT
    }

    public static SafetyStop emptyReply(String partialText) {
        return new SafetyStop(SafetyStopReason.EMPTY_REPLY, "empty response", partialText);
    }

    public static SafetyStop llmFailure(RuntimeException error, String partialText) {
        String message = error.getMessage();
        return new SafetyStop(
                SafetyStopReason.LLM_FAILURE,
                message == null || message.isBlank() ? error.toString() : message,
                partialText);
    }

    /** One sentence — what happened, in the caller's terms. */
    public String describe() {
        return switch (reason) {
            case IDLE_STUCK -> "it repeated the same tool call without making progress (" + detail + ")";
            case EMPTY_REPLY ->
                "the model returned an empty response (no text, no tool call) even after automatic retries";
            case LLM_FAILURE -> "the model call failed (" + detail + ")";
            case WALLCLOCK -> "it reached the per-turn time limit (" + detail + ")";
            case ITERATION_LIMIT -> "it reached the recipe's step limit (" + detail + ")";
        };
    }

    /**
     * Stop text for a worker that closes on the stop: the reply is the
     * parent's only reliable signal (synchronous drivers read the last
     * assistant text, a Working-WS parent gets the FAILED ProcessEvent
     * suppressed), so it states unambiguously that the task is unfinished
     * and the worker is closed.
     */
    public String workerText() {
        StringBuilder sb = new StringBuilder("⚠️ TASK FAILED — this worker was stopped by a safety net: ")
                .append(describe())
                .append(". It is now CLOSED and cannot be resumed. The task is UNFINISHED — do not treat ")
                .append("any text below as an answer, and do not assume the remaining steps ran. To carry ")
                .append("the task further, start a fresh worker (tighter scope, or a different model).");
        if (!partialText.isBlank()) {
            sb.append("\n\nPartial progress:\n\n").append(partialText);
        }
        return sb.toString();
    }

    /**
     * Stop text for a process that stays alive and parks BLOCKED (a chat, or
     * an operator identity whose machine owns the lifecycle): say what
     * stopped the turn and how to go on — the next message continues with
     * the full conversation history.
     */
    public String continuableText() {
        StringBuilder sb = new StringBuilder("⚠️ I stopped this turn because ")
                .append(describe())
                .append(". Reply \"continue\" to pick up from here, or give me new instructions.");
        if (!partialText.isBlank()) {
            sb.append("\n\nProgress so far:\n\n").append(partialText);
        }
        return sb.toString();
    }
}
