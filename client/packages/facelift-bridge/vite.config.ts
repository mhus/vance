import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';

// Single-Page-App entry. Capacitor wraps a WebView around `dist/index.html`;
// every editor inside the shell is a Vue Router route, not a separate HTML.
// See `planning/vance-facelift.md` §3.A — Option A3.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, './src'),
    },
  },
  server: {
    port: 9901,
    // Dev-Server proxies /brain to the local Brain so REST + WS run
    // same-origin in the browser (`pnpm dev`). On-device, the Capacitor
    // WebView talks to the explicit baseUrl in `vance.identity.brainUrl`
    // and CORS must be configured on the Brain to allow
    // `capacitor://localhost`.
    proxy: {
      '/brain': {
        // Dev-server proxy target. Only relevant for `pnpm dev` —
        // the Capacitor WebView in production talks to the per-account
        // brainUrl, not through this proxy.
        target: 'http://localhost:9990',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  build: {
    target: 'es2022',
    outDir: 'dist',
    sourcemap: true,
  },
});
