package de.mhus.vance.brain.wonko;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * When the loop stops on its own (plan §4.5) — besides "nothing left to ask",
 * which the planner reports. Pure logic over the round statistics.
 */
final class WonkoConvergence {

    static final String MAX_ROUNDS = "max-rounds";
    static final String FATIGUE = "fatigue";
    static final String NO_PROGRESS = "no-progress";

    private WonkoConvergence() {}

    /**
     * The reason to stop before asking round {@code round + 1}, or
     * {@code null} to go on.
     */
    static @Nullable String stopReason(List<WonkoState.RoundStat> stats, int round, int maxRounds) {
        if (round >= maxRounds) {
            return MAX_ROUNDS;
        }
        int n = stats.size();
        if (n >= 2 && mostlyLater(stats.get(n - 1)) && mostlyLater(stats.get(n - 2))) {
            return FATIGUE;
        }
        if (n >= 3) {
            int a = stats.get(n - 3).getOpenAfter();
            int b = stats.get(n - 2).getOpenAfter();
            int c = stats.get(n - 1).getOpenAfter();
            if (b >= a && c >= b && c > 0) {
                return NO_PROGRESS;
            }
        }
        return null;
    }

    private static boolean mostlyLater(WonkoState.RoundStat stat) {
        return stat.getAsked() > 0 && stat.getLater() * 2 > stat.getAsked();
    }
}
