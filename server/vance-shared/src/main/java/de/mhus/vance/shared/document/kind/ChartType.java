package de.mhus.vance.shared.document.kind;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The chart-type discriminator carried on the {@code chart.chartType}
 * field. Every type's data-point shape is documented in
 * {@code specification/doc-kind-chart.md} §2.5.
 *
 * <p>Wire form is lowercase ({@code "line"}, {@code "candlestick"}); the
 * enum stores the same and exposes {@link #fromWire} for parsing.
 */
public enum ChartType {
    LINE("line"),
    BAR("bar"),
    AREA("area"),
    SCATTER("scatter"),
    PIE("pie"),
    DONUT("donut"),
    CANDLESTICK("candlestick"),
    HEATMAP("heatmap");

    private static final Map<String, ChartType> BY_WIRE;

    static {
        Map<String, ChartType> m = new java.util.HashMap<>();
        for (ChartType t : values()) m.put(t.wire, t);
        BY_WIRE = Map.copyOf(m);
    }

    private final String wire;

    ChartType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static @Nullable ChartType fromWire(@Nullable String raw) {
        if (raw == null) return null;
        return BY_WIRE.get(raw.trim().toLowerCase());
    }

    /**
     * {@code true} for the chart types whose data-point shape is the
     * {@code {x, y}} pair (line / bar / area / scatter). Used by the
     * codec to dispatch shape validation.
     */
    public boolean isXyShaped() {
        return this == LINE || this == BAR || this == AREA || this == SCATTER;
    }

    /** {@code true} for pie / donut — data points are {@code {name, value}}. */
    public boolean isNamedValueShaped() {
        return this == PIE || this == DONUT;
    }
}
