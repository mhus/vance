import { defineConfig } from 'vitest/config';

// Standalone vitest config. Deliberately does NOT extend the app's
// vite.config.ts — that config wires the module-federation plugin and a
// dev-server middleware stack that break under vitest's module runner.
// The parity tests are plain Node unit tests (no Vue, no bundler), so an
// empty-plugin node environment is all we need.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
