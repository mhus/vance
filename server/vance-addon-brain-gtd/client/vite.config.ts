import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * GTD addon — Module Federation remote. Exposes `./register`, which registers
 * the `application:gtd` kind. Only `vue` is shared; the block editor is bundled
 * per-addon (same posture as journal / wiki / workbook).
 */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_gtd',
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
