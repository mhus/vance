package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceInterest;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pure, deterministic snapshot math for a finance tree — no Spring, no I/O.
 * Produces one {@link NodeSnapshot} per node (pre-order: parent before its
 * children) for a given {@code today}.
 *
 * <p>Model (see {@code planning/app-finance-tree.md} §5a):
 * <ul>
 *   <li><b>Normalisation</b> — everything canonical per year on the fixed
 *       365-day year: a {@code RECURRING} record's base is
 *       {@code value / period.years()}.</li>
 *   <li><b>Interest (v1 linear)</b> — computed on the record's base
 *       {@code value}: {@code value × rate/100 / interestPeriod.years()} per
 *       year. Tracked separately from base. {@code basis} is treated as
 *       {@code VOM_HUNDERT} in v1; {@code compound} is a projection concern.</li>
 *   <li><b>Sign rollup</b> — {@code total = sign × (Σ own + Σ children total)}.
 *       A per-record {@code sign} multiplies just that record.</li>
 *   <li><b>Validity</b> — a {@code RECURRING} record only counts when
 *       {@code today} is inside {@code [validFrom, validTo]} (null = open).</li>
 *   <li><b>One-time</b> — {@code ONE_TIME} records never enter the per-year
 *       rate; their sign-applied sum is reported separately as
 *       {@code oneTimeSum} (temporal placement is a projection concern).</li>
 * </ul>
 */
public final class FinanceCalculator {

    private FinanceCalculator() {
        // utility class
    }

    /** Sign-applied subtree totals threaded up through the recursion. */
    private record Totals(double base, double interest, double oneTime) {}

    /** A subtree result: its totals plus the snapshots in pre-order. */
    private record Result(Totals totals, List<NodeSnapshot> snapshots) {}

    /** Compute snapshots for the whole tree, parent-before-children. */
    public static List<NodeSnapshot> compute(FinanceNode root, LocalDate today) {
        return walk(root, today).snapshots();
    }

    private static Result walk(FinanceNode node, LocalDate today) {
        double ownBase = 0;
        double ownInterest = 0;
        double ownOneTime = 0;
        for (FinanceValue v : node.values()) {
            int rs = recordSign(v);
            if (v.mode() == ValueMode.ONE_TIME) {
                ownOneTime += rs * v.value();
            } else if (activeOn(v, today)) {
                ownBase += rs * basePerYear(v);
                ownInterest += rs * interestPerYear(v);
            }
        }

        List<NodeSnapshot> snapshots = new ArrayList<>();
        double childBase = 0;
        double childInterest = 0;
        double childOneTime = 0;
        List<NodeSnapshot> childSnapshots = new ArrayList<>();
        for (FinanceNode child : node.children()) {
            Result cr = walk(child, today);
            childBase += cr.totals().base();
            childInterest += cr.totals().interest();
            childOneTime += cr.totals().oneTime();
            childSnapshots.addAll(cr.snapshots());
        }

        int sign = node.sign() < 0 ? -1 : 1;
        double base = sign * (ownBase + childBase);
        double interest = sign * (ownInterest + childInterest);
        double oneTime = sign * (ownOneTime + childOneTime);
        double perYear = base + interest;

        snapshots.add(new NodeSnapshot(
                node.name(), perYear,
                perDisplay(perYear, PeriodUnit.MONTH),
                perDisplay(perYear, PeriodUnit.WEEK),
                perDisplay(perYear, PeriodUnit.DAY),
                base, interest, oneTime));
        snapshots.addAll(childSnapshots);
        return new Result(new Totals(base, interest, oneTime), snapshots);
    }

    private static int recordSign(FinanceValue v) {
        Integer s = v.sign();
        if (s == null) return 1;
        return s < 0 ? -1 : 1;
    }

    private static double basePerYear(FinanceValue v) {
        return v.value() / v.period().years();
    }

    private static double interestPerYear(FinanceValue v) {
        FinanceInterest i = v.interest();
        if (i == null) return 0;
        return v.value() * (i.rate() / 100.0) / i.period().years();
    }

    /** Convert an annual rate to its per-unit display value on the fixed year. */
    private static double perDisplay(double perYear, PeriodUnit unit) {
        return perYear * unit.days() / PeriodUnit.YEAR.days();
    }

    private static boolean activeOn(FinanceValue v, LocalDate today) {
        LocalDate from = parseDate(v.validFrom());
        LocalDate to = parseDate(v.validTo());
        if (from != null && today.isBefore(from)) return false;
        if (to != null && today.isAfter(to)) return false;
        return true;
    }

    private static @Nullable LocalDate parseDate(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (DateTimeParseException e) {
            return null; // lenient: unparseable date = unbounded
        }
    }
}
