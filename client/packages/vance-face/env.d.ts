/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
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
