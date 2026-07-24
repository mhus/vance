package de.mhus.vance.addon.brain.finance;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Finance Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans the
 * {@code de.mhus.vance.addon.brain.finance} package so the finance kind
 * handler, service and {@code finance_*} tools register themselves into the
 * Brain context.
 *
 * <p>Self-contained: reuses only Vance-wide facilities (DocumentService, the
 * {@code $meta}-header machinery, the kind-handler SPI). See
 * {@code planning/app-finance-tree.md}.
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.finance",
})
public class FinanceAddon {
}
