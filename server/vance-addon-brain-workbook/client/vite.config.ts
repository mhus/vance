import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Workbook + Canvas addon — Module Federation remote. Exposes the
 * `./register` side-effect entry that registers both kinds
 * (`canvas` + `application:workbook`) with the host's kind-registry.
 *
 * Tiptap is declared shared so a future second consumer of the block
 * editor (if any) resolves to the same ProseMirror instance.
 * `@vance/block-editor` is intentionally NOT shared — workbook packages
 * cause a top-level-await deadlock if shared (see
 * vance-face/vite.config.ts comment).
 */
export default defineConfig({
  base: '',
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // emoji-picker-element registers as a native custom element.
          // Without this hint Vue would try to resolve it as a component.
          isCustomElement: (tag) => tag === 'emoji-picker',
        },
      },
    }),
    federation({
      name: 'vance_addon_workbook',
      filename: 'remoteEntry.js',
      exposes: {
        './register': './src/register.ts',
      },
      shared: {
        vue: { singleton: true, requiredVersion: '^3.5.0' },
        '@tiptap/core': { singleton: true, requiredVersion: '^2.10.0' },
        '@tiptap/pm': { singleton: true, requiredVersion: '^2.10.0' },
        '@tiptap/vue-3': { singleton: true, requiredVersion: '^2.10.0' },
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
