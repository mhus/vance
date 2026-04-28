import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';

// One Rollup input per top-level HTML file. Add new editor HTMLs here as they
// are implemented — see specification/web-ui.md §3 for the full list.
const editorEntries = {
  index: resolve(__dirname, 'index.html'),
  document: resolve(__dirname, 'document-editor.html'),
  inbox: resolve(__dirname, 'inbox.html'),
  scopes: resolve(__dirname, 'scopes.html'),
  recipes: resolve(__dirname, 'recipes.html'),
};

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/brain': {
        target: process.env.VITE_BRAIN_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, './src'),
      '@components': resolve(__dirname, './src/components'),
      '@composables': resolve(__dirname, './src/composables'),
    },
  },
  build: {
    // ES2022 unlocks top-level await — used by every editor's `main.ts` to
    // call `ensureAuthenticated()` before mounting. Browser baseline is fine
    // for our target audience (modern Chromium/Firefox/Safari from 2022+).
    target: 'es2022',
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      input: editorEntries,
    },
  },
});
