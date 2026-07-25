package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** One hit in the document picker used to anchor new entries. */
@GenerateTypeScript("binder")
public record BinderDocItem(
        String id,
        String path,
        @Nullable String title,
        @Nullable String kind,
        @Nullable String mimeType) {}
