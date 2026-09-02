import { defineConfig, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import { federation } from '@module-federation/vite';
import { resolve, extname } from 'node:path';
import { createReadStream, existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import yaml from 'js-yaml';

// Vite 8's native config loader no longer injects the CommonJS dir global —
// use the ESM equivalent (Node 20.11+, satisfied by Vite 8's Node 20.19+ floor).
const pkgDir = import.meta.dirname;

// One Rollup input per top-level HTML file. Add new editor HTMLs here as they
// are implemented — see specification/web-ui.md §3 for the full list.
//
// `index` is the workspace SHELL: cortex, chat, inbox, documents and the
// launcher are routes inside it (src/shell/router.ts), not entries of their
// own. The rule is that whatever answers `/` is index.html, so making the
// shell serve `/` decided its file name — and left nginx untouched, since its
// `index` directive and `try_files` fallback already point there.
const editorEntries = {
  index: resolve(pkgDir, 'index.html'),
  // The door. Its own entry precisely because it must boot when the shell
  // bundle cannot — see src/login/LoginApp.vue.
  login: resolve(pkgDir, 'login.html'),
  scopes: resolve(pkgDir, 'scopes.html'),
  tools: resolve(pkgDir, 'tools.html'),
  insights: resolve(pkgDir, 'insights.html'),
  runs: resolve(pkgDir, 'runs.html'),
  users: resolve(pkgDir, 'users.html'),
  profile: resolve(pkgDir, 'profile.html'),
  'connected-accounts': resolve(pkgDir, 'connected-accounts.html'),
  'oauth-providers': resolve(pkgDir, 'oauth-providers.html'),
  'tool-templates': resolve(pkgDir, 'tool-templates.html'),
  'setting-forms': resolve(pkgDir, 'setting-forms.html'),
  // Generic host for federated addon "areas": addon.html?addon=<id> loads the
  // addon's ./area expose (e.g. the Simple-Auth permission-grant UI).
  addon: resolve(pkgDir, 'addon.html'),
};

// No history-fallback plumbing for the shell's routes (`/cortex`, `/chat`, …),
// and that is a measured absence rather than an oversight. Production nginx
// already resolves them: its `index` directive and its `try_files` fallback
// both name index.html, which IS the shell. And Vite's dev server does the
// same for free — `appType` defaults to `'spa'`, which serves index.html for
// any extensionless path that accepts HTML. Verified by requesting `/cortex`
// from a dev server that had no such middleware and getting the shell entry.
//
// If `appType: 'mpa'` is ever set here, that changes and the routes need an
// explicit rewrite in the middleware below.

// Build-time remotes list is intentionally empty — addons are discovered
// and registered at RUNTIME via `registerRemotes()` from
// `@module-federation/runtime` (see platform/addonRegistry.ts). The host
// fetches `/face/addons` at boot, gets a list of installed addon ids, and
// registers each remote dynamically; the `register()` expose is then pulled
// when a document of a kind that addon declares is opened. No more
// rebuild-on-new-addon, no more dual-mapping
// (vite.config + addonRegistry). New addons just need their
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
  const workspaceRoot = resolve(pkgDir, '..', '..', '..');

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
        // the dev server returns 404, addonRegistry discovery bails,
        // and runtime Kind contributions never reach the registry.
        if (pathname === '/face/addons') {
          const addonsRoot = resolve(workspaceRoot, 'server');
          let entries: {
            name: string; path: string; tile?: unknown; profile?: unknown; menu?: unknown;
            kinds?: unknown; eager?: unknown;
          }[] = [];
          try {
            entries = readdirSync(addonsRoot, { withFileTypes: true })
              .filter((d) => d.isDirectory() && d.name.startsWith('vance-addon-brain-'))
              .map((d) => d.name.substring('vance-addon-brain-'.length))
              .filter((id) =>
                existsSync(resolve(addonsRoot, `vance-addon-brain-${id}`, 'client', 'dist', 'remoteEntry.js')),
              )
              .map((id) => {
                const entry: {
                  name: string; path: string; tile?: unknown; profile?: unknown;
                  menu?: unknown; kinds?: unknown; eager?: unknown;
                } = {
                  name: id,
                  path: `bundled:${id}`,
                };
                // Optional landing-tile metadata — single source: the addon's
                // server manifest META-INF/vance-addon.yaml `tile:` block (the
                // same file the brain reads for prod /face/addons). Lets IndexApp
                // render a tile without loading the federation remote.
                try {
                  const manifest = yaml.load(
                    readFileSync(
                      resolve(
                        addonsRoot,
                        `vance-addon-brain-${id}`,
                        'src',
                        'main',
                        'resources',
                        'META-INF',
                        'vance-addon.yaml',
                      ),
                      'utf8',
                    ),
                  ) as {
                    tile?: unknown; profile?: unknown; menu?: unknown;
                    kinds?: unknown; eager?: unknown;
                  } | null;
                  if (manifest?.tile) entry.tile = manifest.tile;
                  // The profile tab an addon contributes — same single
                  // source as the landing tile, so the profile screen can
                  // build its strip without loading a remote first.
                  if (manifest?.profile) entry.profile = manifest.profile;
                  // The Cortex menu entries an addon contributes. Declarative
                  // for a sharper reason than the tile: a menu entry has no
                  // document kind to trigger a lazy load on, so reading it
                  // here is what keeps the bundle out of every page load.
                  // See platform/loadCortexMenu.ts.
                  if (Array.isArray(manifest?.menu)) entry.menu = manifest.menu;
                  // The document kinds an addon contributes. Same single
                  // source again, and the reason the host no longer loads
                  // every remote at boot: it can defer the fetch until a
                  // document of that kind is opened. See
                  // platform/addonRegistry.ts.
                  if (Array.isArray(manifest?.kinds)) entry.kinds = manifest.kinds;
                  // Opt out of the lazy path — for contributions no kind can
                  // trigger (a block-editor block; see AddonDto.eager).
                  if (manifest?.eager === true) entry.eager = true;
                } catch {
                  // no manifest — addon contributes neither tile nor tab
                }
                return entry;
              });
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
      '@': resolve(pkgDir, './src'),
      '@components': resolve(pkgDir, './src/components'),
      '@composables': resolve(pkgDir, './src/composables'),
    },
  },
  build: {
    // ES2022 unlocks top-level await — used by every editor's `main.ts` to
    // call `ensureAuthenticated()` before mounting. Browser baseline is fine
    // for our target audience (modern Chromium/Firefox/Safari from 2022+).
    target: 'es2022',
    outDir: 'dist',
    sourcemap: true,
    // Vite 8 defaults CSS minification to LightningCSS, which hard-errors on
    // the throwaway rules Tailwind's JIT emits when it mistakes a regex
    // character class (e.g. `/[-:.]/` in an addon component) for an arbitrary
    // property. esbuild — the pre-Vite-8 default — tolerates them, so keep it.
    cssMinify: 'esbuild',
    rollupOptions: {
      input: editorEntries,
    },
  },
});
