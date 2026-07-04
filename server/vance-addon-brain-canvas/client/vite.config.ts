import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_canvas',
      filename: 'remoteEntry.js',
      exposes: {
        './register': './src/register.ts',
      },
      shared: {
        vue: { singleton: true, requiredVersion: '^3.5.0' },
        '@vue-flow/core': { singleton: true, requiredVersion: '^1.48.0' },
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
