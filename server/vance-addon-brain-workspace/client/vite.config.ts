import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';

/**
 * Workspace addon — Module Federation remote. Exposes the
 * WorkspaceAppKind wrapper that mounts inside cortex/notepad as the
 * editor for application:workspace documents.
 */
export default defineConfig({
  base: '',
  plugins: [
    vue(),
    federation({
      name: 'vance_addon_workspace',
      filename: 'remoteEntry.js',
      exposes: {
        './WorkspaceAppKind': './src/WorkspaceAppKind.vue',
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
