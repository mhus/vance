package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one handwriting sheet inside a scribblebook. */
@GenerateTypeScript("scribble")
public record ScribblebookPageView(
        String id,
        String path,
        String relativePath,
        String title,
        @Nullable String description) {}
