import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Wiki addon — Module Federation remote. Exposes the `./register`
 * side-effect entry that registers the `application:wiki` kind with
 * the host's kind-registry.
 *
 * Only `vue` is shared. Tiptap / prosemirror are intentionally NOT
 * shared: @module-federation/vite can't share @tiptap/pm's subpath
 * exports (broken loadShare factory), and the block registry is
 * per-bundle anyway — no ProseMirror Nodes cross bundle boundaries.
 * This addon bundles its own copy of the block editor (same posture as
 * the workbook addon; see its vite.config.ts).
 */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_wiki',
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
