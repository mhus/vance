import { defineConfig } from 'vitest/config';

// Standalone, and deliberately NOT extending vite.config.ts — that config
// wires the module-federation plugin, which breaks under vitest's module
// runner (same reason the face keeps its own config).
//
// `environment: 'node'` is enough because the modules under test are logic
// plus refs: no DOM, no browser. The frame's DOM-side wiring stays in the
// component, thin on purpose.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
