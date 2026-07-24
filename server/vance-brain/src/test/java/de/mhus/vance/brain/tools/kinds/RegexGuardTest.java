package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression (code-review-2 injection): an untrusted regex from an LLM tool
 * param must not be able to pin the lane thread with a slow / catastrophically
 * backtracking match. The deadline-checking CharSequence aborts once the
 * wall-clock budget passes, regardless of how much backtracking remains.
 */
class RegexGuardTest {

    @Test
    @Timeout(10) // whole test must finish fast — proves the match aborted, not hung
    void slowMatch_abortsOnceBudgetPasses() {
        // O(n^2) scan: `x+y` retries `x+` from every position over a long
        // all-`x` line with no `y`. That is far more character-reads than the
        // 100ms budget allows, so the deadline check trips mid-scan.
        Pattern p = Pattern.compile("x+y");
        String input = "x".repeat(200_000);
        long deadline = System.nanoTime() + 100_000_000L; // 100ms

        assertThatThrownBy(() -> RegexGuard.find(p, input, deadline))
                .isInstanceOf(RegexGuard.RegexBudgetExceeded.class);
    }

    @Test
    void alreadyExpiredBudget_abortsImmediately() {
        Pattern p = Pattern.compile("a+b");
        assertThatThrownBy(() -> RegexGuard.find(p, "a".repeat(4096), System.nanoTime() - 1))
                .isInstanceOf(RegexGuard.RegexBudgetExceeded.class);
    }

    @Test
    void wellBehavedPattern_matchesWithinBudget() {
        Pattern p = Pattern.compile("foo");
        long deadline = System.nanoTime() + 2_000_000_000L;
        assertThat(RegexGuard.find(p, "a foo b", deadline)).isTrue();
        assertThat(RegexGuard.find(p, "no match here", deadline)).isFalse();
    }
}
