import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Canvas addon — Module Federation remote. Exposes the Tiptap-based
 * editor; vance-face's federation host loads it via
 * /addons/canvas/remoteEntry.js (configured with `type: 'module'`).
 */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_canvas',
      filename: 'remoteEntry.js',
      exposes: {
        './CanvasEditor': './src/CanvasEditor.vue',
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
