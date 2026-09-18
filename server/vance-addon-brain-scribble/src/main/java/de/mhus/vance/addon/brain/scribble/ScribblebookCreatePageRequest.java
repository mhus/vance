package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Request body to create a new sheet inside a scribblebook. */
@GenerateTypeScript("scribble")
public record ScribblebookCreatePageRequest(
        @Nullable String title, @Nullable String slug) {}
