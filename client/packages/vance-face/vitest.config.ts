import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath } from 'node:url';

// Standalone vitest config. Deliberately does NOT extend the app's
// vite.config.ts — that config wires the module-federation plugin and a
// dev-server middleware stack that break under vitest's module runner.
// The `@` alias lets tests import `@/…` source modules (e.g. docTypeRegistry)
// directly, and heavy Vue SFC imports are stubbed per-test with vi.mock.
//
// The Vue plugin is here so a test CAN mount a real component; the default
// environment stays `node` so the suite stays fast, and the handful of tests
// that need a DOM opt in per file with
//
//   // @vitest-environment jsdom
//
// Two things were untestable without this and are the reason it exists: the
// lazy-KaTeX path in MarkdownView (a render that changes after a dynamic
// import resolves) and the call-side half of the WS leak guard — the
// composables clean up in `onBeforeUnmount`, which needs a real component
// instance. See planning/web-ui-reorg.md §3 and §4.7.
//
// jsdom rather than happy-dom, and that is not a preference. Under happy-dom,
// DOMPurify silently strips every `<span>` — `sanitizeHtml('<span>hi</span>
// <b>y</b>')` returns `hi<b>y</b>` — which guts both the KaTeX output and the
// pending-source placeholder that the math test is about. The same call under
// jsdom returns the span untouched. A harness that quietly deletes the markup
// under test is worse than none.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
