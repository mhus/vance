import { type WebUiTheme } from './webUiSession';
/**
 * Apply {@code value} (or the active stored theme when omitted) to the
 * document. Idempotent — safe to call from boot, profile saves, or
 * any other re-entry point. Sets up (or tears down) the media-query
 * listener so "auto" tracks the OS preference live.
 */
export declare function applyTheme(value?: WebUiTheme): void;
//# sourceMappingURL=themeWeb.d.ts.map