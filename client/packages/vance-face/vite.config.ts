import { defineConfig, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';
import { resolve, extname } from 'node:path';
import { createReadStream, existsSync, readdirSync, statSync } from 'node:fs';

// One Rollup input per top-level HTML file. Add new editor HTMLs here as they
// are implemented — see specification/web-ui.md §3 for the full list.
const editorEntries = {
  index: resolve(__dirname, 'index.html'),
  documents: resolve(__dirname, 'documents.html'),
  inbox: resolve(__dirname, 'inbox.html'),
  chat: resolve(__dirname, 'chat.html'),
  scopes: resolve(__dirname, 'scopes.html'),
  tools: resolve(__dirname, 'tools.html'),
  insights: resolve(__dirname, 'insights.html'),
  users: resolve(__dirname, 'users.html'),
  profile: resolve(__dirname, 'profile.html'),
  cortex: resolve(__dirname, 'cortex.html'),
  // notepad merged into cortex (2026-06); this stub keeps old bookmarks
  // and inline server redirects (3rd-party tools, history links)
  // working by forwarding to /cortex.html with all query params intact.
  notepad: resolve(__dirname, 'notepad.html'),
  'connected-accounts': resolve(__dirname, 'connected-accounts.html'),
  'oauth-providers': resolve(__dirname, 'oauth-providers.html'),
  'tool-templates': resolve(__dirname, 'tool-templates.html'),
  'setting-forms': resolve(__dirname, 'setting-forms.html'),
};

// Build-time remotes list is intentionally empty — addons are discovered
// and registered at RUNTIME via `registerRemotes()` from
// `@module-federation/runtime` (see loadAddonRegistrations.ts). The host
// fetches `/face/addons` at boot, gets a list of installed addon ids,
// and registers each remote dynamically before calling its `register()`
// expose. No more rebuild-on-new-addon, no more dual-mapping
// (vite.config + loadAddonRegistrations). New addons just need their
// `client/dist/remoteEntry.js` reachable under `/addons/<id>/` — the
// dev-server middleware below already path-scans for that, the
// production Docker entrypoint symlinks `/shared/addons/<id>/<ver>/face`.
const addonRemotes: Record<string, any> = {};

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
function vanceAddonDevServe(): Plugin {
  const mimeTypes: Record<string, string> = {
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
          let entries: { name: string; path: string }[] = [];
          try {
            entries = readdirSync(addonsRoot, { withFileTypes: true })
              .filter((d) => d.isDirectory() && d.name.startsWith('vance-addon-brain-'))
              .map((d) => d.name.substring('vance-addon-brain-'.length))
              .filter((id) =>
                existsSync(resolve(addonsRoot, `vance-addon-brain-${id}`, 'client', 'dist', 'remoteEntry.js')),
              )
              .map((id) => ({ name: id, path: `bundled:${id}` }));
          } catch {
            entries = [];
          }
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify(entries));
          return;
        }

        const match = pathname.match(/^\/addons\/([^/]+)\/(.+)$/);
        if (!match) return next();

        const [, addonId, relPath] = match;
        const addonDist = resolve(
          workspaceRoot,
          'server',
          `vance-addon-brain-${addonId}`,
          'client',
          'dist',
        );
        const filePath = resolve(addonDist, relPath);

        // Path traversal guard — the resolved file must stay below the
        // addon's dist tree.
        if (!filePath.startsWith(addonDist + '/') && filePath !== addonDist) {
          res.statusCode = 403;
          res.end();
          return;
        }

        if (!existsSync(filePath) || !statSync(filePath).isFile()) {
          console.warn(
            `[vance-addon-dev-serve] 404 ${pathname} — run 'pnpm --filter @vance-addon/${addonId} build' to refresh ${addonDist}`,
          );
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
        // NB: Tiptap / prosemirror are deliberately NOT shared here. The
        // host (vance-face) does not bundle the block editor — it lives in
        // the addons (workbook, kanban, …). Declaring a shared dep the host
        // can't PROVIDE registers an empty factory in the shared scope; the
        // first addon to loadShare it then crashes with "factory is not a
        // function". The addons that DO bundle the editor declare the
        // Tiptap/prosemirror singletons among themselves (see their
        // vite.config.ts) and dedupe there. See addon-system.md §7d.
      },
    }),
    vanceAddonDevServe(),
  ],
  server: {
    // FACE_PORT / BRAIN_PORT override the defaults for local multi-pod
    // dev: `BRAIN_PORT=9991 FACE_PORT=9901 pnpm dev` pairs one face
    // dev-server with one brain instance. See package.json scripts
    // `dev:1` and `dev:2`.
    port: Number(process.env.FACE_PORT ?? 9900),
    proxy: {
      '/brain': {
        // Dev-server proxy target — only used by `pnpm dev`. Production
        // bundles never see this; the deployed face is same-origin-
        // served by the brain in docker / k8s, and the (forthcoming)
        // runtime config.json carries the public URL when the two
        // are split.
        target: `http://localhost:${process.env.BRAIN_PORT ?? 9990}`,
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
