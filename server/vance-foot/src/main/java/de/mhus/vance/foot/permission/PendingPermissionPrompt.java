package de.mhus.vance.foot.permission;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Cross-thread hand-off for an interactive permission prompt.
 *
 * <p>The brain-issued tool call runs on the WebSocket dispatch thread and
 * blocks in {@link #await} until the user answers in the REPL (or the
 * timeout fires). The user's next submitted line is intercepted by
 * {@code ChatInputService} on the REPL input thread and delivered via
 * {@link #offerAnswer}. A {@link SynchronousQueue} carries the choice
 * across the two threads.
 *
 * <p>Only one prompt is active at a time — a {@link ReentrantLock}
 * serialises concurrent asks so two tool calls never fight over the
 * terminal. A second ask that cannot acquire the slot within its timeout
 * resolves to {@code null} (the caller denies).
 *
 * <p>This must <b>not</b> route through {@code ChatInputService}'s async
 * chat executor: that single thread is typically blocked inside the chat
 * round-trip that triggered the tool call, so the answer has to be handled
 * synchronously on the REPL input thread.
 */
@Component
@Slf4j
public class PendingPermissionPrompt {

    private final ChatTerminal terminal;
    private final ReentrantLock promptLock = new ReentrantLock();
    private final AtomicReference<@Nullable BlockingQueue<PermissionChoice>> active =
            new AtomicReference<>();

    public PendingPermissionPrompt(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    /** True while a prompt is waiting for the user's answer. */
    public boolean hasActive() {
        return active.get() != null;
    }

    /**
     * Blocks the calling (WS dispatch) thread until the user answers or
     * {@code timeoutMs} elapses. {@code printMenu} is run once the slot is
     * acquired, just before waiting. Returns the chosen {@link PermissionChoice},
     * or {@code null} on timeout / interruption / failure to acquire the slot.
     */
    public @Nullable PermissionChoice await(Runnable printMenu, long timeoutMs) {
        boolean locked;
        try {
            locked = promptLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!locked) {
            log.warn("permission prompt: another prompt held the slot past timeout — denying");
            return null;
        }
        BlockingQueue<PermissionChoice> queue = new ArrayBlockingQueue<>(1);
        active.set(queue);
        try {
            printMenu.run();
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            active.set(null);
            promptLock.unlock();
        }
    }

    /**
     * Delivers a user-submitted line as the answer to the active prompt.
     * Returns {@code true} when the line was consumed by the prompt (the
     * caller must then NOT route it to the brain). A blank line or no
     * active prompt returns {@code false} (the line passes through). An
     * unparseable answer is consumed (returns {@code true}) and re-prompts.
     */
    public boolean offerAnswer(String line) {
        BlockingQueue<PermissionChoice> queue = active.get();
        if (queue == null) {
            return false;
        }
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        PermissionChoice choice = parse(trimmed);
        if (choice == null) {
            terminal.warn("Please answer 1–4: [1] allow once  [2] allow always  "
                    + "[3] deny once  [4] deny always.");
            return true;
        }
        // offer (not put): if the waiter already timed out the queue has no
        // taker — drop the late answer rather than block the REPL thread.
        boolean delivered = queue.offer(choice);
        if (!delivered) {
            log.debug("permission answer '{}' arrived after timeout — dropped", trimmed);
        }
        return true;
    }

    private static @Nullable PermissionChoice parse(String trimmed) {
        try {
            return PermissionChoice.fromMenuNumber(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
