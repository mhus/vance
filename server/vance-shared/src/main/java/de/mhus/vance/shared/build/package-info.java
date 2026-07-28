/**
 * Build metadata surfaced at runtime.
 *
 * <p>Holds the shared startup logger that prints {@code vance.build.version}
 * / {@code vance.build.time} once per boot. Consumed by every app that
 * component-scans {@code de.mhus.vance.shared} (brain, anus). Foot cannot
 * depend on shared, so it logs its version separately.
 */
@NullMarked
package de.mhus.vance.shared.build;

import org.jspecify.annotations.NullMarked;
