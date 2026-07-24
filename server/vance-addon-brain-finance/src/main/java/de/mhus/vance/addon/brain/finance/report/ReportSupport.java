package de.mhus.vance.addon.brain.finance.report;

import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import java.time.LocalDate;
import java.util.Locale;

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

    /** Money format for sheet cell strings — two decimals, locale-neutral. */
    static String money(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Trim float noise on a numeric chart value. */
    static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
