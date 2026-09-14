package de.mhus.vance.addon.brain.workbook.action;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of one button run. {@code message} is an optional human-readable
 * summary (score, confirmation) that the client may surface as a toast —
 * the action result itself is the document change, pushed live via the
 * documents channel.
 */
public record ButtonActionResult(@Nullable String message) {}
