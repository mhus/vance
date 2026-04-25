package de.mhus.vance.foot.ui;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Tracks whether the REPL currently owns the terminal exclusively
 * (i.e. processing a user command, between {@code readLine} calls), or
 * is waiting for user input with an active prompt.
 *
 * <p>Output paths use this to decide between two strategies:
 * <ul>
 *   <li><b>Exclusive</b> — write directly to the terminal, no newlines,
 *       safe for in-place append (chat streaming).</li>
 *   <li><b>Not exclusive</b> — must use JLine's
 *       {@code LineReader.printAbove(...)} or buffer until exclusive,
 *       otherwise the prompt the user is editing gets corrupted.</li>
 * </ul>
 *
 * <p>The REPL flips the flag in a try/finally around input processing.
 */
@Component
public class PromptGate {

    private final AtomicBoolean exclusive = new AtomicBoolean(false);

    /** REPL is processing input — terminal is ours, no active prompt. */
    public void enterExclusive() {
        exclusive.set(true);
    }

    /** REPL is about to call {@code readLine} again — back off the terminal. */
    public void exitExclusive() {
        exclusive.set(false);
    }

    public boolean isExclusive() {
        return exclusive.get();
    }
}
