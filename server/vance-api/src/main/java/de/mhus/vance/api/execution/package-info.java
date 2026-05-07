/**
 * Cross-side execution registry payloads. Carry foot-side shell-job
 * state (start, output-tick, terminal transition) up to the brain so
 * the brain-central registry can index every execution it knows about
 * regardless of which side spawned it.
 */
@NullMarked
package de.mhus.vance.api.execution;

import org.jspecify.annotations.NullMarked;
