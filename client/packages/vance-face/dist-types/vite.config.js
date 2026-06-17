import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';
import { resolve, extname } from 'node:path';
import { createReadStream, existsSync, readdirSync, statSync } from 'node:fs';
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
    cortex: resolve(__dirname, 'cortex.html'),
    'connected-accounts': resolve(__dirname, 'connected-accounts.html'),
    'oauth-providers': resolve(__dirname, 'oauth-providers.html'),
    'tool-templates': resolve(__dirname, 'tool-templates.html'),
    'setting-forms': resolve(__dirname, 'setting-forms.html'),
};
// Static addon remote map. v1: hardcoded one entry per first-party
// addon, keyed by the addon's federation name (matches the remote's
// vite.config.ts `name:`). Etappe 4 swaps this for a /face/manifest.json
// fetched at boot, so admin-installed addons can be discovered without
// a vance-face rebuild. URL path matches the convention the face Docker
// image's entrypoint sets up: nginx symlinks /shared/addons/<id>/<ver>/face
// → /usr/share/nginx/html/addons/<id>/.
// `type: 'module'` tells the Federation runtime to load the remoteEntry
// via <script type="module"> instead of a classic script. Without it
// the host throws "Cannot use import statement outside a module"
// because Vite emits the remoteEntry with top-level `import`s.
// The Record<string, string> shape in the @module-federation/vite TS
// surface doesn't expose the object form — but the plugin's actual
// schema (RemoteObjectConfig) supports it; cast through `any`.
const addonRemotes = {
    vance_addon_slideshow: {
        name: 'vance_addon_slideshow',
        entry: '/addons/slideshow/remoteEntry.js',
        type: 'module',
    },
    vance_addon_kanban: {
        name: 'vance_addon_kanban',
        entry: '/addons/kanban/remoteEntry.js',
        type: 'module',
    },
    vance_addon_calendar: {
        name: 'vance_addon_calendar',
        entry: '/addons/calendar/remoteEntry.js',
        type: 'module',
    },
};
/**
 * Dev-server middleware that resolves `/addons/<id>/<path>` to the
 * federation remote sitting in the matching server-side addon module's
 * `client/dist/` directory. In production the face Docker image
 * entrypoint symlinks `/shared/addons/<id>/<ver>/face` into
 * `/usr/share/nginx/html/addons/<id>/`; here we bridge the same URL
 * shape onto the workbench filesystem, so `pnpm dev` Just Works as
 * long as each addon has been built once (`pnpm --filter
 * @vance-addon/<id> build`).
 *
 * Addon discovery is purely path-based: `<id>` segment of the URL maps
 * to `repos/vance/server/vance-addon-brain-<id>/client/dist/`. No
 * config update needed when a new addon arrives; once it builds, its
 * URL works.
 */
function vanceAddonDevServe() {
    const mimeTypes = {
        '.js': 'application/javascript',
        '.mjs': 'application/javascript',
        '.css': 'text/css',
        '.json': 'application/json',
        '.map': 'application/json',
        '.html': 'text/html',
        '.svg': 'image/svg+xml',
        '.png': 'image/png',
        '.woff': 'font/woff',
        '.woff2': 'font/woff2',
    };
    const workspaceRoot = resolve(__dirname, '..', '..', '..');
    return {
        name: 'vance-addon-dev-serve',
        apply: 'serve',
        configureServer(server) {
            server.middlewares.use((req, res, next) => {
                const rawUrl = req.url ?? '';
                const pathname = rawUrl.split('?')[0];
                // /face/addons — dev-mode stand-in for the snapshot file that
                // the face Docker entrypoint writes from the brain at boot
                // (deployment/docker/face/docker-entrypoint.sh). Each addon
                // with a built dist/ shows up as `bundled:<id>` so the loader's
                // path-scheme dispatch matches the prod shape. Without this
                // the dev server returns 404, loadAddonRegistrations() bails,
                // and runtime Kind contributions never reach the registry.
                if (pathname === '/face/addons') {
                    const addonsRoot = resolve(workspaceRoot, 'server');
                    let entries = [];
                    try {
                        entries = readdirSync(addonsRoot, { withFileTypes: true })
                            .filter((d) => d.isDirectory() && d.name.startsWith('vance-addon-brain-'))
                            .map((d) => d.name.substring('vance-addon-brain-'.length))
                            .filter((id) => existsSync(resolve(addonsRoot, `vance-addon-brain-${id}`, 'client', 'dist', 'remoteEntry.js')))
                            .map((id) => ({ name: id, path: `bundled:${id}` }));
                    }
                    catch {
                        entries = [];
                    }
                    res.setHeader('Content-Type', 'application/json');
                    res.end(JSON.stringify(entries));
                    return;
                }
                const match = pathname.match(/^\/addons\/([^/]+)\/(.+)$/);
                if (!match)
                    return next();
                const [, addonId, relPath] = match;
                const addonDist = resolve(workspaceRoot, 'server', `vance-addon-brain-${addonId}`, 'client', 'dist');
                const filePath = resolve(addonDist, relPath);
                // Path traversal guard — the resolved file must stay below the
                // addon's dist tree.
                if (!filePath.startsWith(addonDist + '/') && filePath !== addonDist) {
                    res.statusCode = 403;
                    res.end();
                    return;
                }
                if (!existsSync(filePath) || !statSync(filePath).isFile()) {
                    console.warn(`[vance-addon-dev-serve] 404 ${pathname} — run 'pnpm --filter @vance-addon/${addonId} build' to refresh ${addonDist}`);
                    return next();
                }
                res.setHeader('Content-Type', mimeTypes[extname(filePath).toLowerCase()] ?? 'application/octet-stream');
                createReadStream(filePath).pipe(res);
            });
        },
    };
}
export default defineConfig({
    plugins: [
        vue(),
        federation({
            name: 'vance_face',
            remotes: addonRemotes,
            // Only true npm singletons are shared — vue, pinia, vue-i18n.
            // The workspace packages @vance/components and @vance/shared
            // are intentionally NOT shared: declaring them creates a circular
            // top-level-await chain between loadShare__<pkg> and the impl
            // chunk that deadlocks the host boot. Each remote (addon) bundles
            // its own copy instead — a few KB of duplication, but reliable.
            // If we ever need cross-remote singleton enforcement for V*, the
            // proper fix is to publish @vance/components as a real npm
            // package and share it like vue.
            shared: {
                vue: { singleton: true, requiredVersion: '^3.5.0' },
                pinia: { singleton: true },
                'vue-i18n': { singleton: true },
            },
        }),
        vanceAddonDevServe(),
    ],
    server: {
        port: 9900,
        proxy: {
            '/brain': {
                // Dev-server proxy target — only used by `pnpm dev`. Production
                // bundles never see this; the deployed face is same-origin-
                // served by the brain in docker / k8s, and the (forthcoming)
                // runtime config.json carries the public URL when the two
                // are split.
                target: 'http://localhost:9990',
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