package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Request body for {@code POST /capture} — quick unprocessed capture into the Inbox. */
@GenerateTypeScript("gtd")
public record GtdCaptureRequest(
        String title,
        @Nullable String note) {}
