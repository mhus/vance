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
     * Whether an answer could actually arrive. The REPL input thread is
     * the only caller of {@link #offerAnswer}, and it exists exactly
     * while the live region is attached — on a dumb terminal (CI, piped
     * stdin, IntelliJ Run window) no key reader runs and typed lines are
     * discarded, so asking there would always time out. Callers that
     * must not block on an unanswerable prompt check this first.
     */
    public boolean canAsk() {
        return liveRegion.isAttached();
    }

    /**
     * Shows {@code label} as an inline input prompt (rendered as the live
     * input-line prefix on a PTY, or a static line on a dumb terminal) and
     * blocks until the user submits a line or {@code timeoutMs} elapses.
     * The answer is never written to the ↑/↓ history; when {@code masked}
     * the input row is echoed as bullets. On completion the prompt plus its
     * answer are committed to the scrollback (masked → bullets). Returns the
     * raw submitted line (possibly empty), or {@code null} on timeout /
     * interruption / failure to acquire the slot.
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
        // On a real PTY the label is rendered inline as the input-line prefix
        // (so it reads as "label + typed answer"); on a dumb / REST-driven
        // terminal there is no live input line, so print it as a static line.
        boolean live = liveRegion.isAttached();
        String answer = null;
        try {
            if (live) {
                liveRegion.setPromptLabel(label);
            } else {
                terminal.info(label);
            }
            if (masked) {
                liveRegion.setMaskInput(true);
            }
            answer = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (masked) {
                liveRegion.setMaskInput(false);
            }
            if (live) {
                liveRegion.setPromptLabel(null);
            }
            active.set(null);
            promptLock.unlock();
        }
        // Persist the completed prompt to the scrollback so it stays visible
        // after the live prompt line is cleared. Masked answers are shown as
        // fixed bullets so a password's length is not revealed.
        if (live && answer != null) {
            String shown = masked ? (answer.isEmpty() ? "" : "••••") : answer;
            terminal.info(label + shown);
        }
        return answer;
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
