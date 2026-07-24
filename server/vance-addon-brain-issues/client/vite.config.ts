import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/** Issues addon — Module Federation remote. Exposes `./register`. */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_issues',
      filename: 'remoteEntry.js',
      exposes: { './register': './src/register.ts' },
      shared: { vue: { singleton: true, requiredVersion: '^3.5.0' } },
      dts: false,
    }),
  ],
  build: { target: 'esnext', minify: false, cssCodeSplit: true },
});
