package de.mhus.vance.addon.brain.binder;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Response of {@code POST /addon/binder/rebuild}. */
@GenerateTypeScript("binder")
public record RebuildResponse(
        String folder,
        String indexPath,
        @Nullable String indexLink,
        int entryCount,
        long missingCount) {}
