package de.mhus.vance.addon.brain.scribble.model;

/**
 * The sheet raster — the coordinate space stroke points live in. Fixed per
 * sheet, never the viewport: zoom and pan are pure view state and never
 * touch the file. Defaults to A4 portrait at 150 dpi
 * ({@code 1240 × 1754}); other values are just values, not special cases.
 */
public record ScribbleSize(int w, int h) {

    /** Default raster: A4 portrait at 150 dpi. */
    public static ScribbleSize a4Portrait() {
        return new ScribbleSize(1240, 1754);
    }

    public boolean isPositive() {
        return w > 0 && h > 0;
    }
}
