import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Common Desktop addon — Module Federation remote. Exposes the
 * launcher/status board; vance-face's federation host loads it via
 * /addons/common-desktop/remoteEntry.js. Shared singleton: vue only —
 * workspace packages bundle a copy per remote, see
 * specification/addon-system.md §5.3.
 */
export default defineConfig({
  // Federation remote served from `/addons/<id>/` — empty base avoids
  // Vite's `'/'+dep` chunk preload paths 404'ing (see the kanban /
  // calendar addon vite.config.ts for the full explanation).
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_common_desktop',
      filename: 'remoteEntry.js',
      exposes: {
        './DesktopBoard': './src/DesktopBoard.vue',
        './register': './src/register.ts',
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
