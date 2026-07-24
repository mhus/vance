package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceInterest;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceProjection;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ProjectionPeriod;
import de.mhus.vance.addon.brain.finance.model.ProjectionRow;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pure, deterministic time-range projection for a finance tree — no Spring,
 * no I/O. Buckets {@code [from, to)} by {@code granularity} (calendar-aligned
 * steps) and integrates each node's value records over the actual days in each
 * bucket. A pure function of {@code (root, from, to, granularity)}.
 *
 * <p>Per-period contribution of a value record (see
 * {@code planning/app-finance-tree.md} §5b):
 * <ul>
 *   <li><b>RECURRING base</b> — pro-rata by active days:
 *       {@code basePerYear × activeDays/365}, where {@code activeDays} is the
 *       overlap of the bucket with the record's validity window.</li>
 *   <li><b>Interest, linear</b> ({@code compound=false}) — flow like the base:
 *       {@code interestPerYear × activeDays/365}.</li>
 *   <li><b>Interest, compound</b> ({@code compound=true}, with a
 *       {@code validFrom} anchor) — the base {@code value} grows as a principal
 *       from {@code validFrom}; the interest booked in a bucket is
 *       {@code value × ((1+r)^nEnd − (1+r)^nStart)} with {@code r = rate/100}
 *       per interest-period and {@code n} the interest-periods elapsed to the
 *       bucket bounds. Without a {@code validFrom} anchor it falls back to
 *       linear.</li>
 *   <li><b>ONE_TIME</b> — the lump lands wholly in the bucket containing
 *       {@code validFrom}; interest on one-time lumps is a v2 refinement.</li>
 * </ul>
 * Sign rollup is identical to the snapshot: {@code total = sign × (Σ own + Σ
 * children)}, with a per-record {@code sign} multiplying just that record.
 */
public final class FinanceProjector {

    private static final int MAX_PERIODS = 2048;

    private FinanceProjector() {
        // utility class
    }

    public static FinanceProjection project(FinanceNode root, LocalDate from, LocalDate to,
                                            PeriodUnit granularity) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("projection range `from` must be before `to`");
        }

        List<ProjectionPeriod> periods = new ArrayList<>();
        List<LocalDate[]> bounds = new ArrayList<>();
        LocalDate cursor = from;
        while (cursor.isBefore(to)) {
            LocalDate next = advance(cursor, granularity);
            LocalDate end = next.isAfter(to) ? to : next;
            periods.add(new ProjectionPeriod(label(cursor, granularity),
                    cursor.toString(), end.toString()));
            bounds.add(new LocalDate[] {cursor, end});
            if (periods.size() > MAX_PERIODS) {
                throw new IllegalArgumentException(
                        "projection exceeds " + MAX_PERIODS
                                + " periods; widen the granularity or narrow the range");
            }
            cursor = next;
        }

        List<String> order = new ArrayList<>();
        preOrder(root, order);

        List<Map<String, Double>> perPeriod = new ArrayList<>();
        for (LocalDate[] b : bounds) {
            Map<String, Double> amounts = new HashMap<>();
            walkPeriod(root, b[0], b[1], amounts);
            perPeriod.add(amounts);
        }

        List<ProjectionRow> rows = new ArrayList<>();
        for (String name : order) {
            List<Double> amounts = new ArrayList<>(perPeriod.size());
            double total = 0;
            for (Map<String, Double> m : perPeriod) {
                double a = m.getOrDefault(name, 0.0);
                amounts.add(a);
                total += a;
            }
            rows.add(new ProjectionRow(name, amounts, total));
        }
        return new FinanceProjection(periods, rows);
    }

    // ── Tree walk (per bucket) ────────────────────────────────────

    private static double walkPeriod(FinanceNode node, LocalDate ps, LocalDate pe,
                                     Map<String, Double> out) {
        double own = 0;
        for (FinanceValue v : node.values()) {
            own += periodContribution(v, ps, pe);
        }
        double childSum = 0;
        for (FinanceNode child : node.children()) {
            childSum += walkPeriod(child, ps, pe, out);
        }
        int sign = node.sign() < 0 ? -1 : 1;
        double total = sign * (own + childSum);
        out.put(node.name(), total);
        return total;
    }

    private static double periodContribution(FinanceValue v, LocalDate ps, LocalDate pe) {
        int rs = recordSign(v);
        if (v.mode() == ValueMode.ONE_TIME) {
            LocalDate d = parseDate(v.validFrom());
            if (d != null && !d.isBefore(ps) && d.isBefore(pe)) return rs * v.value();
            return 0;
        }
        LocalDate from = parseDate(v.validFrom());
        LocalDate to = parseDate(v.validTo());
        long activeDays = overlapDays(ps, pe, from, to);
        if (activeDays == 0) return 0;
        double base = v.value() / v.period().years() * (activeDays / 365.0);
        double interest = interestForPeriod(v, ps, pe, from, activeDays);
        return rs * (base + interest);
    }

    private static double interestForPeriod(FinanceValue v, LocalDate ps, LocalDate pe,
                                            @Nullable LocalDate from, long activeDays) {
        FinanceInterest i = v.interest();
        if (i == null) return 0;
        double annual = v.value() * (i.rate() / 100.0) / i.period().years();
        if (!i.compound() || from == null) {
            return annual * (activeDays / 365.0); // linear flow
        }
        // Compound on `value` as a principal anchored at `from`.
        double r = i.rate() / 100.0;
        double ipDays = i.period().days();
        double nStart = Math.max(0, ChronoUnit.DAYS.between(from, ps) / ipDays);
        double nEnd = Math.max(0, ChronoUnit.DAYS.between(from, pe) / ipDays);
        return v.value() * (Math.pow(1 + r, nEnd) - Math.pow(1 + r, nStart));
    }

    /** Days of {@code [ps, pe)} that fall inside the inclusive validity window. */
    private static long overlapDays(LocalDate ps, LocalDate pe,
                                    @Nullable LocalDate from, @Nullable LocalDate to) {
        LocalDate lo = (from == null || from.isBefore(ps)) ? ps : from;
        LocalDate hiExcl = pe;
        if (to != null) {
            LocalDate toExcl = to.plusDays(1); // validTo is inclusive
            if (toExcl.isBefore(hiExcl)) hiExcl = toExcl;
        }
        long days = ChronoUnit.DAYS.between(lo, hiExcl);
        return Math.max(0, days);
    }

    // ── Bucketing ─────────────────────────────────────────────────

    private static LocalDate advance(LocalDate d, PeriodUnit g) {
        return switch (g) {
            case DAY -> d.plusDays(1);
            case WEEK -> d.plusWeeks(1);
            case MONTH -> d.plusMonths(1);
            case YEAR -> d.plusYears(1);
        };
    }

    private static String label(LocalDate from, PeriodUnit g) {
        return switch (g) {
            case DAY, WEEK -> from.toString();
            case MONTH -> String.format(Locale.ROOT, "%04d-%02d", from.getYear(), from.getMonthValue());
            case YEAR -> String.format(Locale.ROOT, "%04d", from.getYear());
        };
    }

    // ── Pure helpers ──────────────────────────────────────────────

    private static void preOrder(FinanceNode node, List<String> out) {
        out.add(node.name());
        for (FinanceNode child : node.children()) preOrder(child, out);
    }

    private static int recordSign(FinanceValue v) {
        Integer s = v.sign();
        if (s == null) return 1;
        return s < 0 ? -1 : 1;
    }

    private static @Nullable LocalDate parseDate(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
