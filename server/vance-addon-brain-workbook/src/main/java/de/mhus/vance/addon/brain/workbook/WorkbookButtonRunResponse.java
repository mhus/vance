package de.mhus.vance.addon.brain.workbook;

import org.jspecify.annotations.Nullable;

/**
 * Response of {@code POST …/addon/workbook/button/run}: the action's
 * optional summary message (score, confirmation) — the actual effect is the
 * document change, pushed to open editors via the documents channel.
 */
public record WorkbookButtonRunResponse(@Nullable String message) {}
