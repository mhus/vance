package de.mhus.vance.addon.brain.finance.report;

import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parameter bag handed to a {@link FinanceReportProcessor} — the values
 * collected from the processor's param form plus the standard range keys
 * ({@code from}, {@code to}, {@code granularity}). Lenient typed accessors;
 * unknown/blank values read as {@code null}/fallback.
 */
public record ReportParams(Map<String, Object> values) {

    public ReportParams {
        if (values == null) values = Map.of();
    }

    public static ReportParams of(Map<String, Object> values) {
        return new ReportParams(values);
    }

    public @Nullable String getString(String key) {
        Object v = values.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s.trim();
    }

    public @Nullable LocalDate getDate(String key) {
        String s = getString(key);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public PeriodUnit getGranularity(PeriodUnit fallback) {
        PeriodUnit u = PeriodUnit.parse(getString("granularity"));
        return u == null ? fallback : u;
    }
}
