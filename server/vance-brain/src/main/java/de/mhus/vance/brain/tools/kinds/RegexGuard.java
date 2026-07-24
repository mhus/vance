package de.mhus.vance.brain.tools.kinds;

import java.util.regex.Pattern;

/**
 * Runs an (untrusted, LLM-supplied) regex against text under a wall-clock
 * budget so a catastrophic-backtracking pattern can't pin the lane thread at
 * 100% CPU (ReDoS). A plain {@code String} matcher can't be interrupted, but a
 * backtracking matcher re-reads characters constantly — so a {@link CharSequence}
 * that checks a deadline in {@code charAt} aborts the match without any watchdog
 * thread. A single shared deadline across all lines of one tool call bounds both
 * the single-catastrophic-line and the many-lines cases.
 */
final class RegexGuard {

    private RegexGuard() {}

    /** Thrown when the regex work exceeds its wall-clock budget. */
    static final class RegexBudgetExceeded extends RuntimeException {
        RegexBudgetExceeded() {
            super("regex evaluation exceeded its time budget "
                    + "(possible catastrophic backtracking)");
        }
    }

    /**
     * {@code pattern.matcher(line).find()} but aborting with
     * {@link RegexBudgetExceeded} once {@code deadlineNanos}
     * ({@link System#nanoTime()} scale) has passed.
     */
    static boolean find(Pattern pattern, String line, long deadlineNanos) {
        return pattern.matcher(new DeadlineCharSequence(line, deadlineNanos)).find();
    }

    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long deadlineNanos;
        private int ops;

        DeadlineCharSequence(CharSequence delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            // Check every 1024 reads to keep the nanoTime overhead negligible on
            // well-behaved patterns while still bounding a runaway one.
            if ((++ops & 0x3FF) == 0 && System.nanoTime() > deadlineNanos) {
                throw new RegexBudgetExceeded();
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
