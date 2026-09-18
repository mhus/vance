package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a scribblebook scan — the app manifest view + sheet list. */
@GenerateTypeScript("scribble")
public record ScribblebookView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        @Nullable String landingPagePath,
        @Nullable String landingPageId,
        List<ScribblebookPageView> pages) {}
