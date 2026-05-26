/**
 * Wire-contract DTOs for the link-preview proxy endpoint. The
 * Web-UI fetches OpenGraph metadata for external links it renders
 * in chat messages through {@code /brain/{tenant}/link-preview} —
 * a server-side CORS-bypass plus shared cache.
 *
 * <p>Served by {@code de.mhus.vance.brain.tools.web.LinkPreviewController}.
 */
@NullMarked
package de.mhus.vance.api.web;

import org.jspecify.annotations.NullMarked;
