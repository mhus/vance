/**
 * Finance-tree addon for the Vance Brain — first-party.
 *
 * <p>Bundles the {@code kind: finance-tree} document kind: a hierarchical
 * income/expense model ({@link de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument}),
 * its YAML+JSON codec ({@link de.mhus.vance.addon.brain.finance.FinanceTreeCodec})
 * and the kind-handler registration. Loaded by Spring Boot via
 * {@code META-INF/spring/.../AutoConfiguration.imports} pointing at
 * {@link de.mhus.vance.addon.brain.finance.FinanceAddon}.
 *
 * <p>Concept + phases: {@code planning/app-finance-tree.md}.
 */
@NullMarked
package de.mhus.vance.addon.brain.finance;

import org.jspecify.annotations.NullMarked;
