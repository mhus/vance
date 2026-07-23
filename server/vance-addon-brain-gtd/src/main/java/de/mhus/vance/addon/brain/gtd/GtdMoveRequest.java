package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /move} — move an action to a bucket. Sets the
 * {@code when} attribute; {@code date} is required for {@code upcoming}. The
 * Inbox transition additionally relocates the file.
 */
@GenerateTypeScript("gtd")
public record GtdMoveRequest(
        String bucket,
        @Nullable String date) {}
