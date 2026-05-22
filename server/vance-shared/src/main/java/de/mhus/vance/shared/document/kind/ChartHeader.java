package de.mhus.vance.shared.document.kind;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Document-level chart metadata. {@code chartType} is the discriminator
 * that decides the data-point shape per series and the renderer.
 * {@code stacked} is only meaningful for bar / area / line; {@code smooth}
 * only for line / area.
 *
 * <p>Spec: {@code specification/doc-kind-chart.md} §2.2.
 */
public record ChartHeader(
        ChartType chartType,
        @Nullable String title,
        @Nullable String subtitle,
        boolean legend,
        boolean stacked,
        boolean smooth) {

    public ChartHeader {
        Objects.requireNonNull(chartType, "chartType");
    }

    /** Defaults for a freshly-built header: legend on, no stack, no smoothing. */
    public static ChartHeader of(ChartType type) {
        return new ChartHeader(type, null, null, true, false, false);
    }
}
