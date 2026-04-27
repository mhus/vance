/**
 * Wire-contract types for the Vogon strategy engine. Strategy-spec
 * DTOs (Phases, Gates, Checkpoints, …) and the persistent
 * StrategyState that lives on a Vogon think-process. Bundled YAML
 * is loaded into {@link de.mhus.vance.api.vogon.StrategySpec}
 * instances.
 *
 * <p>See {@code specification/vogon-engine.md} for the full
 * subsystem spec.
 */
@NullMarked
package de.mhus.vance.api.vogon;

import org.jspecify.annotations.NullMarked;
