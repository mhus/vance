package de.mhus.vance.addon.brain.finance.report;

import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceProjection;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ProjectionRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared helpers for the built-in report processors. */
final class ReportSupport {

    static final String YAML_MIME = "application/yaml";

    private ReportSupport() {
        // utility class
    }

    /** A resolved projection range: bounds + granularity (default MONTH). */
    record Range(LocalDate from, LocalDate to, PeriodUnit granularity) {}

    static Range resolveRange(String type, ReportParams params) {
        LocalDate from = params.getDate("from");
        LocalDate to = params.getDate("to");
        if (from == null || to == null) {
            throw new IllegalArgumentException(
                    "finance report '" + type + "' requires 'from' and 'to' dates (ISO yyyy-MM-dd)");
        }
        return new Range(from, to, params.getGranularity(PeriodUnit.MONTH));
    }

    /**
     * Projection rows that count: drops the rows of nodes sitting below a
     * {@code disabled} ancestor. The disabled node itself stays (as its hard
     * {@code 0} row), so the visible rows still add up.
     */
    static List<ProjectionRow> countingRows(FinanceProjection proj, FinanceNode root) {
        Set<String> hidden = new HashSet<>();
        collectHidden(root, false, hidden);
        List<ProjectionRow> out = new ArrayList<>();
        for (ProjectionRow r : proj.rows()) {
            if (!hidden.contains(r.name())) out.add(r);
        }
        return out;
    }

    private static void collectHidden(FinanceNode node, boolean underDisabled, Set<String> out) {
        if (underDisabled) out.add(node.name());
        boolean childrenHidden = underDisabled || node.disabled();
        for (FinanceNode child : node.children()) collectHidden(child, childrenHidden, out);
    }

    /** Money format for sheet cell strings — two decimals, locale-neutral. */
    static String money(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Trim float noise on a numeric chart value. */
    static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
