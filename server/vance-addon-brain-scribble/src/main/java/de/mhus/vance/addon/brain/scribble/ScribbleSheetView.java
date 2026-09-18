package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** A scribble sheet with its document identity — the editor's read/save view. */
@GenerateTypeScript("scribble")
public record ScribbleSheetView(
        String id, String path, @Nullable String title, ScribbleSheetDto sheet) {}
