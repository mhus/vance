package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Wire DTO for one ink stroke. Compact wire keys match the on-disk
 * grammar: {@code tool} ({@code "pen"}), {@code color} (palette index
 * {@code "1"}–{@code "4"}), {@code width} ({@code "s"|"m"|"l"}) and the
 * points as {@code [[x, y, pressure], …]}.
 */
@GenerateTypeScript("scribble")
public record ScribbleStrokeDto(String tool, String color, String width, List<List<Double>> points) {}
