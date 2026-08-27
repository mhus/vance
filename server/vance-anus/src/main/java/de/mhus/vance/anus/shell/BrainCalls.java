package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.brain.AnusBrainClient.BrainCallException;
import java.util.function.Supplier;

/**
 * Turns a failed brain call into text instead of letting it out as an
 * exception.
 *
 * <p>Every command in this shell returns its errors as a string, and the reason
 * is measured rather than stylistic: Spring Shell wraps anything thrown in a
 * {@code CommandExecutionException}, which under {@code --sudo} surfaces as
 * "Unable to execute command project claim" with the actual reason nowhere to
 * be seen. {@code ClusterCommands.withPod} and {@code ProjectCommands.confirmed}
 * already say this; what was missing is the same treatment for the one failure
 * every {@code /internal/} command shares.
 *
 * <p>That failure is the common case, not an edge: {@code AnusBrainClient}
 * refuses the call when {@code vance.anus.brain.internal-token} is unset, and
 * unset is the shipped default — the property appears in no
 * {@code application.yml}. The message it throws names the property and what to
 * set it to, which is exactly the sentence the operator needs and exactly the
 * sentence that was being swallowed.
 */
final class BrainCalls {

    private BrainCalls() {}

    /**
     * Runs a command body that talks to the brain over {@code /internal/}.
     *
     * @return the body's own output, or the reason the call could not be made,
     *     in the parenthesised form the rest of the shell uses for refusals
     */
    static String text(Supplier<String> body) {
        try {
            return body.get();
        } catch (BrainCallException e) {
            return "(" + e.getMessage() + ")";
        }
    }
}
