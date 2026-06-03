import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';
import { resolve } from 'node:path';
// One Rollup input per top-level HTML file. Add new editor HTMLs here as they
// are implemented — see specification/web-ui.md §3 for the full list.
const editorEntries = {
    index: resolve(__dirname, 'index.html'),
    app: resolve(__dirname, 'app.html'),
    documents: resolve(__dirname, 'documents.html'),
    inbox: resolve(__dirname, 'inbox.html'),
    chat: resolve(__dirname, 'chat.html'),
    scopes: resolve(__dirname, 'scopes.html'),
    tools: resolve(__dirname, 'tools.html'),
    insights: resolve(__dirname, 'insights.html'),
    users: resolve(__dirname, 'users.html'),
    profile: resolve(__dirname, 'profile.html'),
    scheduler: resolve(__dirname, 'scheduler.html'),
    scripts: resolve(__dirname, 'scripts.html'),
    'connected-accounts': resolve(__dirname, 'connected-accounts.html'),
    'oauth-providers': resolve(__dirname, 'oauth-providers.html'),
    'tool-templates': resolve(__dirname, 'tool-templates.html'),
    'setting-forms': resolve(__dirname, 'setting-forms.html'),
};
// Static addon remote map. v1: hardcoded one entry per first-party
// addon, keyed by the addon's federation name (matches the remote's
// vite.config.ts `name:`). Etappe 3 swaps this for a /face/manifest.json
// fetched at boot, so admin-installed addons can be discovered without
// a vance-face rebuild. URL path matches the convention the face Docker
// image's entrypoint sets up: nginx symlinks /shared/addons/<id>/<ver>/face
// → /usr/share/nginx/html/addons/<artifactId>/.
const addonRemotes = {
    vance_addon_slideshow: '/addons/vance-addon-brain-slideshow/remoteEntry.js',
};
export default defineConfig({
    plugins: [
        vue(),
        federation({
            name: 'vance_face',
            remotes: addonRemotes,
            // Singletons addons rely on. The shared block must mirror the
            // remote's `shared:` declarations (slideshow's vite.config.ts).
            // pinia + vue-i18n appear here for parity with future addons —
            // slideshow doesn't import them itself.
            shared: {
                vue: { singleton: true, requiredVersion: '^3.5.0' },
                '@vance/components': { singleton: true },
                '@vance/shared': { singleton: true },
                pinia: { singleton: true },
                'vue-i18n': { singleton: true },
            },
        }),
    ],
    server: {
        port: 9900,
        proxy: {
            '/brain': {
                target: process.env.VITE_BRAIN_URL ?? 'http://localhost:9990',
                changeOrigin: true,
                ws: true,
            },
        },
    },
    resolve: {
        alias: {
            '@': resolve(__dirname, './src'),
            '@components': resolve(__dirname, './src/components'),
            '@composables': resolve(__dirname, './src/composables'),
        },
    },
    build: {
        // ES2022 unlocks top-level await — used by every editor's `main.ts` to
        // call `ensureAuthenticated()` before mounting. Browser baseline is fine
        // for our target audience (modern Chromium/Firefox/Safari from 2022+).
        target: 'es2022',
        outDir: 'dist',
        sourcemap: true,
        rollupOptions: {
            input: editorEntries,
        },
    },
});
//# sourceMappingURL=vite.config.js.map