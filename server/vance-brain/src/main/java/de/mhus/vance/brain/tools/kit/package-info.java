/**
 * Server-tools that expose the kit subsystem to the LLM:
 * {@code kit_status}, {@code kit_install}, {@code kit_update},
 * {@code kit_apply}, {@code kit_export}. All five are non-primary
 * — discoverable via {@code find_tools} but not loaded into the
 * default tool catalog of every think-process.
 */
@NullMarked
package de.mhus.vance.brain.tools.kit;

import org.jspecify.annotations.NullMarked;
