import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Journal addon — Module Federation remote. Exposes the `./register`
 * side-effect entry that registers the `application:journal` kind with
 * the host's kind-registry.
 *
 * Only `vue` is shared. Tiptap / prosemirror are intentionally NOT
 * shared (same posture as the wiki / workbook addons); the block editor
 * is bundled per-addon.
 */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_journal',
      filename: 'remoteEntry.js',
      exposes: {
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
