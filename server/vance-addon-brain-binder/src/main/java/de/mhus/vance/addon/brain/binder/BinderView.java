package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a binder scan — manifest header + resolved entry list. */
@GenerateTypeScript("binder")
public record BinderView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        @Nullable String landingRef,
        List<BinderEntryView> entries) {}
