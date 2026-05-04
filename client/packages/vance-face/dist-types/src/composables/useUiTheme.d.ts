/**
 * Build-time UI theme assembled from Vite `VITE_VANCE_*` env vars.
 *
 * Resolved once at module load — Vite inlines `import.meta.env` at
 * build time, so the values never change at runtime and there's no
 * point making this reactive. To switch palettes, edit `.env` (or
 * `.env.local`) and rebuild.
 *
 * See `packages/vance-face/.env.example` for the env-var grammar and
 * the rationale for using CSS strings here (rather than the foot
 * client's JLine-style expressions).
 */
export interface BubblePalette {
    fg?: string;
    bg?: string;
}
export interface UiTheme {
    worker: BubblePalette;
    user: BubblePalette;
    assistant: BubblePalette;
    system: BubblePalette;
    /** Truncation cap for worker chat echoes; 0 disables. */
    lineMaxChars: number;
}
export declare const uiTheme: UiTheme;
/**
 * Convert a {@link BubblePalette} to an inline `style` object suitable
 * for {@code :style} binding. Returns `null` when both fields are
 * absent so the caller can fall through to the DaisyUI default classes.
 */
export declare function paletteStyle(p: BubblePalette): Record<string, string> | null;
//# sourceMappingURL=useUiTheme.d.ts.map