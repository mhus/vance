package de.mhus.vance.foot.ui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Cross-thread hand-off for a free-form line prompt (e.g. the {@code /login}
 * flow asking for username / URL / password). Mirrors
 * {@code PendingPermissionPrompt}: the asking side runs on a background
 * thread and blocks in {@link #ask}; the user's next submitted line is
 * delivered on the REPL input thread via {@link #offerAnswer}.
 *
 * <p>The prompt must run off the REPL input thread — that thread is the one
 * that will deliver the answer, so a synchronous read on it would deadlock.
 *
 * <p>Only one prompt is active at a time (a {@link ReentrantLock} serialises
 * asks). While active, a blank line is a valid answer (interpreted by the
 * caller, e.g. "accept default" or "cancel") rather than being swallowed.
 */
@Component
@Slf4j
public class PendingLinePrompt {

    private final LiveRegion liveRegion;
    private final ChatTerminal terminal;
    private final ReentrantLock promptLock = new ReentrantLock();
    private final AtomicReference<@Nullable BlockingQueue<String>> active = new AtomicReference<>();

    public PendingLinePrompt(@Lazy LiveRegion liveRegion, ChatTerminal terminal) {
        this.liveRegion = liveRegion;
        this.terminal = terminal;
    }

    /** True while a prompt is waiting for the user's answer. */
    public boolean hasActive() {
        return active.get() != null;
    }

    /**
     * Prints {@code label} and blocks until the user submits a line or
     * {@code timeoutMs} elapses. When {@code masked}, the input row is
     * echoed as bullets and the answer is neither echoed nor written to
     * history. Returns the raw submitted line (possibly empty), or
     * {@code null} on timeout / interruption / failure to acquire the slot.
     */
    public @Nullable String ask(String label, boolean masked, long timeoutMs) {
        boolean locked;
        try {
            locked = promptLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!locked) {
            log.warn("line prompt: another prompt held the slot past timeout");
            return null;
        }
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        active.set(queue);
        try {
            terminal.info(label);
            if (masked) {
                liveRegion.setMaskInput(true);
            }
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (masked) {
                liveRegion.setMaskInput(false);
            }
            active.set(null);
            promptLock.unlock();
        }
    }

    /**
     * Delivers a user-submitted line as the answer to the active prompt.
     * Returns {@code true} when a prompt was active and consumed the line
     * (the caller must then NOT route it to the brain / dispatcher).
     */
    public boolean offerAnswer(@Nullable String line) {
        BlockingQueue<String> queue = active.get();
        if (queue == null) {
            return false;
        }
        boolean delivered = queue.offer(line == null ? "" : line);
        if (!delivered) {
            log.debug("line-prompt answer arrived after timeout — dropped");
        }
        return true;
    }
}
