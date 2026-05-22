package de.mhus.vance.shared.document.kind;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Axis scale type — matches ECharts axis types. Default is
 * {@link #CATEGORY} for the x-axis and {@link #VALUE} for the y-axis;
 * the {@link ChartAxis} promotion sets those when {@code type} is absent.
 *
 * <p>Spec: {@code specification/doc-kind-chart.md} §2.3.
 */
public enum AxisType {
    CATEGORY("category"),
    VALUE("value"),
    TIME("time"),
    LOG("log");

    private static final Map<String, AxisType> BY_WIRE;

    static {
        Map<String, AxisType> m = new java.util.HashMap<>();
        for (AxisType t : values()) m.put(t.wire, t);
        BY_WIRE = Map.copyOf(m);
    }

    private final String wire;

    AxisType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static @Nullable AxisType fromWire(@Nullable String raw) {
        if (raw == null) return null;
        return BY_WIRE.get(raw.trim().toLowerCase());
    }
}
