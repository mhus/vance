/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
  export default component;
}

// Module Federation remotes. Each first-party addon exposes its editor
// component(s) under a federation name (see the addon's vite.config.ts).
// Stub declarations let vance-face's vue-tsc type-check imports that
// resolve to URLs at runtime, not workspace files.
declare module 'vance_addon_slideshow/SlideshowApp' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<{ projectId: string; folder: string; title?: string }>;
  export default component;
}

declare module 'vance_addon_kanban/KanbanBoard' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<{ projectId: string; folder: string; title?: string }>;
  export default component;
}

declare module 'vance_addon_calendar/CalendarPlanner' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<{ projectId: string; folder: string; title?: string }>;
  export default component;
}

declare module 'vance_addon_calendar/CalendarView' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<Record<string, unknown>>;
  export default component;
}

// Optional ./register exposes — addons that contribute Kind codecs or
// other host-side side effects implement these. Loaded at boot by
// vance-face/src/platform/loadAddonRegistrations.ts. The function may
// be absent at runtime; the loader handles that.
declare module 'vance_addon_slideshow/register' {
  export function register(): void;
}
declare module 'vance_addon_kanban/register' {
  export function register(): void;
}
declare module 'vance_addon_calendar/register' {
  export function register(): void;
}
declare module 'vance_addon_canvas/register' {
  export function register(): void;
}
declare module 'vance_addon_workspace/register' {
  export function register(): void;
}

interface ImportMetaEnv {
  // Note: no VITE_BRAIN_URL here — runtime config lives in
  // `/config.json` (see vance-face/public/config.json + the docker
  // entrypoint that overwrites it at pod start). Build-time env vars
  // would re-bake the URL into every bundle and prevent image reuse
  // across deployments.
  readonly VITE_VANCE_COLOR_WORKER?: string;
  readonly VITE_VANCE_COLOR_USER_FG?: string;
  readonly VITE_VANCE_COLOR_USER_BG?: string;
  readonly VITE_VANCE_COLOR_ASSISTANT_FG?: string;
  readonly VITE_VANCE_COLOR_ASSISTANT_BG?: string;
  readonly VITE_VANCE_COLOR_SYSTEM_FG?: string;
  readonly VITE_VANCE_COLOR_SYSTEM_BG?: string;
  readonly VITE_VANCE_LINE_MAX_CHARS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
