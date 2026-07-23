import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Simple-Auth addon — Module Federation remote. Exposes the permission-grant
 * management area; vance-face's generic addon host (`addon.html?addon=simpleauth`)
 * loads it via /addons/simpleauth/remoteEntry.js. Shared singleton: vue only —
 * @vance/shared + @vance/components bundle a copy per remote (same convention as
 * the other addons, see specification/addon-system.md §5.3).
 */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_simpleauth',
      filename: 'remoteEntry.js',
      exposes: {
        './area': './src/PermissionsArea.vue',
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
