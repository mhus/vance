package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Request body for creating a standalone scribble sheet. */
@GenerateTypeScript("scribble")
public record ScribbleCreateSheetRequest(
        String path, @Nullable String title) {}
