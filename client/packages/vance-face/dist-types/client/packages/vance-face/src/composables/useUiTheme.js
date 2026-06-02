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
function read(key) {
    // `as Record<...>` keeps TypeScript happy for arbitrary VITE_* keys
    // without forcing every override to be declared in `vite-env.d.ts`.
    const raw = import.meta.env[key];
    if (!raw)
        return undefined;
    const trimmed = raw.trim();
    return trimmed.length > 0 ? trimmed : undefined;
}
function readInt(key, fallback) {
    const raw = read(key);
    if (!raw)
        return fallback;
    const parsed = parseInt(raw, 10);
    return Number.isFinite(parsed) ? parsed : fallback;
}
export const uiTheme = {
    worker: {
        fg: read('VITE_VANCE_COLOR_WORKER'),
    },
    user: {
        fg: read('VITE_VANCE_COLOR_USER_FG'),
        bg: read('VITE_VANCE_COLOR_USER_BG'),
    },
    assistant: {
        fg: read('VITE_VANCE_COLOR_ASSISTANT_FG'),
        bg: read('VITE_VANCE_COLOR_ASSISTANT_BG'),
    },
    system: {
        fg: read('VITE_VANCE_COLOR_SYSTEM_FG'),
        bg: read('VITE_VANCE_COLOR_SYSTEM_BG'),
    },
    lineMaxChars: readInt('VITE_VANCE_LINE_MAX_CHARS', 140),
};
/**
 * Convert a {@link BubblePalette} to an inline `style` object suitable
 * for {@code :style} binding. Returns `null` when both fields are
 * absent so the caller can fall through to the DaisyUI default classes.
 */
export function paletteStyle(p) {
    const out = {};
    if (p.fg)
        out.color = p.fg;
    if (p.bg)
        out.backgroundColor = p.bg;
    return Object.keys(out).length > 0 ? out : null;
}
//# sourceMappingURL=useUiTheme.js.map