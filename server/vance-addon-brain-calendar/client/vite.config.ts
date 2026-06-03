import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Calendar addon — Module Federation remote. Exposes the planner
 * editor (AppEditor surface), the per-document Kind view, and the
 * register() side-effect that populates {@code @vance/kind-registry}
 * with the {@code 'calendar'} Kind entry. vance-face's federation
 * host loads everything via /addons/calendar/remoteEntry.js
 * (configured with {@code type: 'module'}).
 *
 * Shared singletons: vue only — workspace packages (@vance/components,
 * @vance/shared, @vance/kind-registry) bundle a copy per remote, see
 * specification/addon-system.md §5.3. The {@code @vance/kind-registry}
 * Map lives on globalThis so the duplicated copies all point at the
 * same store.
 */
export default defineConfig({
  // Federation remotes are served from `/addons/<id>/` at runtime (face
  // nginx symlink in prod, vite middleware in dev). An absolute base
  // would emit `<link rel=modulepreload href="/assets/X.js">` and try
  // to fetch chunks from the root — 404 when the host is vance-face.
  // Empty base → Vite emits relative URLs that resolve against each
  // chunk's own URL, so `/addons/<id>/assets/<chunk>.js` correctly
  // sibling-imports `<chunk2>.js` no matter where the host mounts us.
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_calendar',
      filename: 'remoteEntry.js',
      exposes: {
        './CalendarPlanner': './src/CalendarPlanner.vue',
        './CalendarView': './src/CalendarView.vue',
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
    // cssCodeSplit MUST be true (Vite default): federation injects CSS
    // by walking the per-expose cssAssetMap in virtualExposes.js. With
    // cssCodeSplit:false Vite bundles all CSS into a single global file
    // and the map is empty, so the host never sees the addon's styles
    // — the editor renders as plain DOM.
    cssCodeSplit: true,
  },
});
