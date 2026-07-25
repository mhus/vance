package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST /addon/binder/entry/section} — set section/title of an entry. */
@GenerateTypeScript("binder")
public record SetSectionRequest(
        String ref,
        @Nullable String section,
        @Nullable String title) {}
