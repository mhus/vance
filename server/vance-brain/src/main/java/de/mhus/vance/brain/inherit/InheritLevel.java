package de.mhus.vance.brain.inherit;

import de.mhus.vance.shared.prak.SpanStrength;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Recipe-level setting for how much of a spawning process's chat
 * history gets pre-pasted into a fresh worker's first turn. See
 * {@code planning/worker-spawn-context.md} for the design rationale.
 *
 * <p>Sealed because the value space mixes flat enum cases ({@code none},
 * {@code summary}, {@code all}) with the parametric {@code last:N} and
 * a {@link SpanStrength}-keyed variant. Pattern-matched at render time
 * to pick the right query path.
 *
 * <p>Parse string forms via {@link #parse(String)}:
 * <pre>
 *   "none"       → {@link None}
 *   "summary"    → {@link SummaryOnly}
 *   "all"        → {@link All}
 *   "weak" /
 *   "normal" /
 *   "strong" /
 *   "pinned"     → {@link ByStrength} with the named minimum
 *   "last:N"     → {@link Last} with N as int
 * </pre>
 */
public sealed interface InheritLevel {

    /** No parent-context block. Worker starts blank. */
    record None() implements InheritLevel { }

    /**
     * Only the parent's active {@code ARCHIVED_CHAT} memory summary
     * (if any). No active history. Use for engines that want a small
     * background anchor without conversational noise (Marvin,
     * Slartibartfast).
     */
    record SummaryOnly() implements InheritLevel { }

    /**
     * Parent's active summary + complete active history. Effectively
     * mirrors what the parent itself sees — compaction-bounded, so
     * size stays manageable even for long sessions. Default for
     * Ford-style reflex workers.
     */
    record All() implements InheritLevel { }

    /**
     * Parent's active summary + active history filtered to messages
     * tagged with {@code STRENGTH:{minStrength}} or stronger. Messages
     * with NO strength tag also pass (OR-untagged semantic) so the
     * filter degrades gracefully when prak has not yet evaluated the
     * span. Use for specialist workers that want the highlights
     * without the chatter.
     */
    record ByStrength(SpanStrength minStrength) implements InheritLevel { }

    /**
     * Parent's last {@code n} active messages, strength filter ignored,
     * summary skipped. Bypass for cases where strength tagging is
     * unreliable or the recipe explicitly wants a recency-only window.
     */
    record Last(int n) implements InheritLevel { }

    /**
     * Parses the recipe-level string into a level. Returns
     * {@link None} for {@code null}, empty, or unrecognised values —
     * unknown forms are tolerated (the spawn pipeline must never crash
     * on a bad recipe-config). Logging is the caller's job.
     */
    static InheritLevel parse(@Nullable String raw) {
        if (raw == null) return new None();
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("none") || s.equals("off") || s.equals("false")) {
            return new None();
        }
        if (s.equals("summary")) return new SummaryOnly();
        if (s.equals("all") || s.equals("full")) return new All();
        if (s.startsWith("last:")) {
            try {
                int n = Integer.parseInt(s.substring("last:".length()).trim());
                if (n > 0) return new Last(n);
            } catch (NumberFormatException ignored) { /* fall through */ }
            return new None();
        }
        // Strength-keyed: weak | normal | strong | pinned.
        try {
            SpanStrength min = SpanStrength.valueOf(s.toUpperCase(Locale.ROOT));
            return new ByStrength(min);
        } catch (IllegalArgumentException ignored) {
            return new None();
        }
    }
}
