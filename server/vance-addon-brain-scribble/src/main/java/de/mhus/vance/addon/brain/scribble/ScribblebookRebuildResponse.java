package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Response of a scribblebook index rebuild. */
@GenerateTypeScript("scribble")
public record ScribblebookRebuildResponse(
        String folder, String indexPath, @Nullable String indexLink, int pageCount) {}
