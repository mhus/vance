/**
 * Report processors for the finance tree — the plug-and-play output layer.
 *
 * <p>A {@link de.mhus.vance.addon.brain.finance.report.FinanceReportProcessor}
 * is a self-registered bean that turns the computed finance model into a
 * document of an <em>existing</em> kind ({@code sheet}, {@code chart}, …). The
 * math lives once in the pure calculator/projector; a processor is pure
 * presentation — it calls the shared math with the report params and formats
 * the result through the target kind's codec (byte-identical round-trip). New
 * report types are new beans; a Kit can contribute its own.
 */
@NullMarked
package de.mhus.vance.addon.brain.finance.report;

import org.jspecify.annotations.NullMarked;
