/**
 * Kit subsystem — git-repo backed bundles of documents, settings and
 * server-tools that can be installed into projects, updated, applied,
 * and exported back. See {@code specification/kits.md} for the full
 * subsystem spec and the file-per-entity / inherit-chain model.
 *
 * <p>Public entry point: {@link KitService}. Everything else (loader,
 * resolver, installer, exporter, yaml mapping) is collaborator code
 * that talks to KitService.
 */
@NullMarked
package de.mhus.vance.brain.kit;

import org.jspecify.annotations.NullMarked;
