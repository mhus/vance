import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Store addon — Module Federation remote. Exposes the store area;
 * vance-face's generic addon host (`addon.html?addon=store`) loads it via
 * /addons/store/remoteEntry.js. Shared singleton: vue only — @vance/shared +
 * @vance/components bundle a copy per remote (same convention as the other
 * addons, see specification/addon-system.md §5.3).
 */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_store',
      filename: 'remoteEntry.js',
      exposes: {
        './area': './src/StoreArea.vue',
      },
      shared: {
        vue: { singleton: true, requiredVersion: '^3.5.0' },
      },
      dts: false,
    }),
  ],
  build: {
    target: 'esnext',
    minify: false,
    cssCodeSplit: true,
  },
});
