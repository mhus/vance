package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTO for a full scribble sheet — display metadata, the sheet raster
 * (flat {@code sizeW}/{@code sizeH}) and the ordered strokes.
 */
@GenerateTypeScript("scribble")
public record ScribbleSheetDto(
        @Nullable String title,
        int sizeW,
        int sizeH,
        List<ScribbleStrokeDto> strokes,
        boolean enabled,
        boolean defaultSheet) {}
