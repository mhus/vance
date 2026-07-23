package de.mhus.vance.addon.brain.gtd;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The heart of the GTD app: maps an action's {@code when}/{@code deadline}
 * and today's date to a {@link GtdBucket}. Pure and deterministic — no Mongo,
 * no clock — so it is exhaustively unit-tested.
 *
 * <p>Rules (first match wins):
 * <ol>
 *   <li>the action sits in {@code inbox/} → {@link GtdBucket#INBOX} (unprocessed,
 *       regardless of {@code when});</li>
 *   <li>a {@code deadline} on or before today → {@link GtdBucket#TODAY}
 *       (a hard due date pulls the action forward);</li>
 *   <li>{@code when == "someday"} → {@link GtdBucket#SOMEDAY};</li>
 *   <li>{@code when == "today"} → {@link GtdBucket#TODAY};</li>
 *   <li>{@code when} is a date: future → {@link GtdBucket#UPCOMING}, else
 *       (today or overdue) → {@link GtdBucket#TODAY};</li>
 *   <li>no {@code when} (or unparseable non-keyword) → {@link GtdBucket#ANYTIME}.</li>
 * </ol>
 */
@Component
public class GtdBucketResolver {

    public static final String WHEN_TODAY = "today";
    public static final String WHEN_SOMEDAY = "someday";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    public GtdBucket bucketOf(boolean inInbox, @Nullable String when,
                              @Nullable String deadline, LocalDate today) {
        if (inInbox) return GtdBucket.INBOX;

        LocalDate dl = tryParse(deadline);
        if (dl != null && !dl.isAfter(today)) return GtdBucket.TODAY;

        String w = when == null ? "" : when.trim().toLowerCase(java.util.Locale.ROOT);
        if (WHEN_SOMEDAY.equals(w)) return GtdBucket.SOMEDAY;
        if (WHEN_TODAY.equals(w)) return GtdBucket.TODAY;

        LocalDate wd = tryParse(when);
        if (wd != null) return wd.isAfter(today) ? GtdBucket.UPCOMING : GtdBucket.TODAY;

        return GtdBucket.ANYTIME;
    }

    /** True when the action is overdue: a past {@code when}-date or {@code deadline}. */
    public boolean isOverdue(@Nullable String when, @Nullable String deadline, LocalDate today) {
        LocalDate dl = tryParse(deadline);
        if (dl != null && dl.isBefore(today)) return true;
        LocalDate wd = tryParse(when);
        return wd != null && wd.isBefore(today);
    }

    private static @Nullable LocalDate tryParse(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), ISO);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
