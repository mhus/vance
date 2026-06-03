import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Slideshow addon — Module Federation remote.
 *
 * Build output (dist/) carries a remoteEntry.js that vance-face loads
 * at runtime via the federation manifest mechanism. Shared singletons
 * (vue, @vance/components, @vance/shared) are provided by the host;
 * the addon does NOT bundle copies, so the V* components in
 * SlideshowApp.vue resolve to the same instances vance-face uses.
 *
 * Output convention matches planning/addon-system.md §1.5: the .vab
 * assembly copies this dist/ tree into the bundle's face/ directory.
 */
export default defineConfig({
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_slideshow',
      filename: 'remoteEntry.js',
      exposes: {
        './SlideshowApp': './src/SlideshowApp.vue',
        './register': './src/register.ts',
      },
      // Only npm singletons are shared (the host configures the same
      // set in vance-face's vite.config.ts). Workspace packages
      // (@vance/components, @vance/shared) are intentionally bundled
      // into this remote's own chunks — declaring them as shared
      // triggers a circular top-level-await between loadShare__<pkg>
      // and the impl chunk that deadlocks app boot.
      shared: {
        vue: { singleton: true, requiredVersion: '^3.5.0' },
      },
      // Skip Module Federation's own .d.ts generator — we emit types
      // via vue-tsc in the same build script (`vue-tsc -b && vite build`).
      // The federation DTS path tries to invoke vue-tsc with an
      // internal tsconfig and fails on the .vue files; this flag avoids
      // a build-noise stack trace without losing any consumer-visible
      // type info.
      dts: false,
    }),
  ],
  build: {
    target: 'esnext',
    minify: false,
    cssCodeSplit: false,
    rollupOptions: {
      // Federation entry is auto-injected by the plugin — no manual
      // input here.
    },
  },
});
