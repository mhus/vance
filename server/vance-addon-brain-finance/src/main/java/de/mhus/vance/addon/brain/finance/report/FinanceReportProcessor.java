package de.mhus.vance.addon.brain.finance.report;

import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import org.jspecify.annotations.Nullable;

/**
 * SPI for a finance report generator. A bean implementing this interface
 * contributes one report {@link #type()} that turns the computed finance model
 * into a document of {@link #outputKind()}. Self-registered (component-scanned)
 * and collected by {@link FinanceReportRegistry} — new report types are new
 * beans, no central switch; a Kit can add its own.
 *
 * <p>A processor is <b>pure presentation</b>: it calls the shared math
 * (calculator/projector) with the {@code params} and formats the result
 * through the target kind's codec. It carries <b>no</b> financial math of its
 * own.
 */
public interface FinanceReportProcessor {

    /** Stable machine type, e.g. {@code "table"}, {@code "series"}. */
    String type();

    /** Human display label for the editor dropdown. */
    String title();

    /** Target document kind produced, e.g. {@code "sheet"}, {@code "chart"}. */
    String outputKind();

    /**
     * Optional processor-specific param form (FormFields YAML) beyond the
     * standard range keys the editor always collects ({@code from}, {@code to},
     * {@code granularity}). {@code null} = no extra fields.
     */
    default @Nullable String paramForm() {
        return null;
    }

    /**
     * Render the report. {@code tree} is the parsed input model; the processor
     * runs the shared math over it with {@code params} and returns a serialised
     * document body for {@link #outputKind()}.
     */
    FinanceReport render(FinanceTreeDocument tree, ReportParams params);
}
