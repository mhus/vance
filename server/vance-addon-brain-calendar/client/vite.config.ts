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
    cssCodeSplit: false,
  },
});
