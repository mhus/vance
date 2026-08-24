import { defineConfig } from 'vitest/config';

// Standalone, and deliberately NOT extending vite.config.ts — that config
// wires the module-federation plugin, which breaks under vitest's module
// runner (same reason the face keeps its own config).
//
// `environment: 'node'` is possible because the sandbox is DOM-free above the
// transport seam: it uses bare timers, guards the two page-lifecycle
// listeners, and takes a GuestTransport for everything else. Tests supply a
// fake transport, so no jsdom and no browser are needed to exercise the
// protocol — which is where every bug so far has lived.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
