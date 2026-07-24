/**
 * The {@code finance_*} LLM tools — thin adapters over
 * {@link de.mhus.vance.addon.brain.finance.FinanceService} and the report
 * registry. They resolve the target document, parse params through the shared
 * codec grammar ({@code FinanceTreeCodec.nodeFromMap}/{@code valueFromMap}),
 * and never carry financial math of their own.
 */
@NullMarked
package de.mhus.vance.addon.brain.finance.tool;

import org.jspecify.annotations.NullMarked;
