package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** Wire DTO for the scribble OCR run — where the transcript landed, how big. */
@GenerateTypeScript("scribble")
public record ScribbleOcrResponse(String mdPath, int chars) {}
