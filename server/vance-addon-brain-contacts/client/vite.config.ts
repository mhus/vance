import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Contacts addon — Module Federation remote. Two exposes: `./register`
 * contributes the `contact` document kind; `./area` is the address book the
 * landing tile opens (`addon.html?addon=contacts`).
 */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_contacts',
      filename: 'remoteEntry.js',
      exposes: {
        './register': './src/register.ts',
        './area': './src/ContactsArea.vue',
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
