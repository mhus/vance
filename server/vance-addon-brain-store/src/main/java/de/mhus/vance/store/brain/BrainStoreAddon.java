package de.mhus.vance.store.brain;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Brain-side store addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans the
 * package so the store controller and its client register.
 *
 * <p>A client of the store, never a copy of it: everything it shows comes
 * from HTTP calls to the store and delivery services, so no part of the
 * selling side runs inside a customer's installation.
 *
 * <p>Spec: {@code planning/kit-store.md} §7 Phase S3.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = BrainStoreAddon.class)
public class BrainStoreAddon {
}
