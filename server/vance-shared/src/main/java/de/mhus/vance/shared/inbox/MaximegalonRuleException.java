package de.mhus.vance.shared.inbox;

import lombok.Getter;

/**
 * A thread invariant refused the operation — not an authorization failure and
 * not a lookup miss, but "this would break the model".
 *
 * <p>Separate from a plain {@code IllegalStateException} because the caller
 * needs to tell the cases apart: the REST surface answers 409 with
 * {@link #getReason()} as a stable code, and the reasons are decisions from
 * {@code planning/maximegalon.md}, not implementation accidents.
 */
@Getter
public class MaximegalonRuleException extends RuntimeException {

    /**
     * The assignee of an open ask may not unsubscribe: a process is waiting on
     * them, and silently going quiet would leave it waiting forever. Delegating
     * is the way out — that is what it exists for.
     */
    public static final String ASSIGNEE_MUST_STAY = "assignee_must_stay";

    /**
     * The thread reached {@code MaximegalonService.MAX_MESSAGES}. The bound
     * exists because the discussion is embedded in the thread document, and an
     * unbounded array walks into the 16 MB limit — a burst document is neither
     * readable nor repairable through the API. For a single matter it is also
     * simply a healthy limit: a thread is supposed to end.
     */
    public static final String MESSAGE_LIMIT_REACHED = "message_limit_reached";

    /**
     * The referenced parent message does not exist, or is itself a reply.
     * Depth is capped at one level — see {@code MaximegalonMessage#parentId}
     * for why that is policy rather than structure.
     */
    public static final String INVALID_PARENT = "invalid_parent";

    private final String reason;

    public MaximegalonRuleException(String reason, String message) {
        super(message);
        this.reason = reason;
    }
}
