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
  // Federation remote served from `/addons/<id>/` — see comment in
  // vance-addon-brain-calendar/client/vite.config.ts for why empty
  // base is required (avoids Vite's `'/'+dep` chunk preload paths
  // 404'ing because the addon isn't mounted at the host root).
  base: '',
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
        // Tiptap / prosemirror are NOT shared: @module-federation/vite can't
        // share @tiptap/pm's subpath exports (broken loadShare factory), and
        // the block registry is per-bundle anyway (blockRegistry.ts) so no
        // Nodes cross bundle boundaries. This addon bundles its own copy.
      },
      dts: false,
    }),
  ],
  build: {
    target: 'esnext',
    minify: false,
    // cssCodeSplit MUST be true (Vite default): federation injects CSS
    // per expose via the virtualExposes.js cssAssetMap. With
    // cssCodeSplit:false the map is empty and the host never sees
    // the addon's styles.
    cssCodeSplit: true,
  },
});
