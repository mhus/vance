package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Where the clipped entry landed. */
@GenerateTypeScript("centauri")
public record ClipResponse(
        String path,
        @Nullable String link) {}
