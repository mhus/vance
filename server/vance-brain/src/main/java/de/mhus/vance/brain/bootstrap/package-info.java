/**
 * Brain boot-time bootstrap. {@link
 * de.mhus.vance.brain.bootstrap.BootstrapBrainService} is the
 * {@code @PostConstruct}-driven entry point: ensures the demo Acme
 * tenant + its users / project groups / projects / seeded documents
 * exist, then provisions the tenant-wide {@code _vance} system project
 * and triggers {@link
 * de.mhus.vance.brain.bootstrap.InitSettingsLoader} to import LLM keys
 * and provider defaults from {@code confidential/init-settings.yaml}.
 *
 * <p>Everything is idempotent — repeated startups against an already
 * populated database are no-ops. Naming convention:
 * <ul>
 *   <li>{@code Bootstrap*} services do idempotent ensure-logic
 *       (matches {@code HomeBootstrapService} and
 *       {@code ServerToolBootstrapService}).</li>
 *   <li>{@code Init*} components implement one-time data import
 *       (loaders, properties classes).</li>
 * </ul>
 */
@NullMarked
package de.mhus.vance.brain.bootstrap;

import org.jspecify.annotations.NullMarked;
