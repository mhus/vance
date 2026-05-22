package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A single data series. {@link #data} is a list of raw data-point values
 * whose shape depends on the document's {@code chartType} (§2.5 of the
 * chart spec). The codec validates the shape per chartType on read and
 * drops invalid entries; valid entries are kept verbatim (Map or List)
 * so the disk form of each point survives the round-trip (object-form
 * stays object-form, tuple-form stays tuple-form).
 *
 * <p>Spec: {@code specification/doc-kind-chart.md} §2.4.
 */
public record ChartSeries(
        String name,
        @Nullable String color,
        List<Object> data,
        Map<String, Object> extra) {

    public ChartSeries {
        Objects.requireNonNull(name, "name");
        if (data == null) data = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }
}
