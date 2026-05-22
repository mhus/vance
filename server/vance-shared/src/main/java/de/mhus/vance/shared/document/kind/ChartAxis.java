package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One axis on a chart. {@link #type} drives the scale interpretation;
 * {@link #categories} is only used with {@link AxisType#CATEGORY} and is
 * optional — when omitted, the categories are inferred from the data.
 *
 * <p>{@code min}/{@code max} force the axis bounds when non-null; they're
 * boxed so the absence of a forced bound stays distinguishable from
 * "force to zero".
 *
 * <p>Spec: {@code specification/doc-kind-chart.md} §2.3.
 */
public record ChartAxis(
        AxisType type,
        @Nullable String label,
        @Nullable Double min,
        @Nullable Double max,
        List<String> categories) {

    public ChartAxis {
        Objects.requireNonNull(type, "type");
        if (categories == null) categories = new ArrayList<>();
    }

    public static ChartAxis defaultX() {
        return new ChartAxis(AxisType.CATEGORY, null, null, null, new ArrayList<>());
    }

    public static ChartAxis defaultY() {
        return new ChartAxis(AxisType.VALUE, null, null, null, new ArrayList<>());
    }
}
