import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Kanban addon — Module Federation remote. Exposes the board editor;
 * vance-face's federation host loads it via /addons/kanban/remoteEntry.js
 * (configured with `type: 'module'`). Shared singletons: vue only —
 * workspace packages (@vance/components, @vance/shared) bundle a copy
 * per remote, see specification/addon-system.md §5.3.
 */
export default defineConfig({
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_kanban',
      filename: 'remoteEntry.js',
      exposes: {
        './KanbanBoard': './src/KanbanBoard.vue',
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
    cssCodeSplit: false,
  },
});
