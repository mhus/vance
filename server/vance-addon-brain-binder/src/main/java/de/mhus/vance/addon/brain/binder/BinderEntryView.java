package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * One resolved binder entry for the Web-UI: the canonical ref plus the
 * live target metadata. {@code exists=false} marks a dangling ref the
 * sidebar renders as "missing".
 */
@GenerateTypeScript("binder")
public record BinderEntryView(
        String ref,
        @Nullable String id,
        String path,
        String title,
        @Nullable String kind,
        @Nullable String mimeType,
        @Nullable String section,
        boolean exists) {}
