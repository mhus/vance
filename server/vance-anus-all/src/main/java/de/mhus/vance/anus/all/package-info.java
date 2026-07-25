/**
 * Dev-bundle entry-point module for the Anus admin shell. Contains
 * {@link de.mhus.vance.anus.all.VanceAnusAllApplication} — a thin delegate to
 * {@link de.mhus.vance.anus.VanceAnusApplication} that runs as the IDE launch
 * target. The bundle's pom pulls in every first-party anus addon (simpleauth,
 * ...) so Spring's auto-configuration scan finds them on the classpath without
 * any per-addon wiring code here.
 */
@NullMarked
package de.mhus.vance.anus.all;

import org.jspecify.annotations.NullMarked;
