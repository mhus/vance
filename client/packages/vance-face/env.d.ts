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

interface ImportMetaEnv {
  readonly VITE_BRAIN_URL?: string;
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
