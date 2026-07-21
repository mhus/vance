import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

// Standalone vitest config. Deliberately does NOT extend the app's
// vite.config.ts — that config wires the module-federation plugin and a
// dev-server middleware stack that break under vitest's module runner.
// Unit tests here are plain Node (no bundler); the `@` alias lets tests
// import `@/…` source modules (e.g. docTypeRegistry) directly, and heavy
// Vue SFC imports are stubbed per-test with vi.mock.
export default defineConfig({
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
