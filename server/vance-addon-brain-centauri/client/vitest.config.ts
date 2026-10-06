import { defineConfig } from 'vitest/config';

// Standalone, and deliberately NOT extending vite.config.ts — that config
// wires the module-federation plugin, which breaks under vitest's module
// runner (same reason the face and the bistromath addon keep their own).
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
