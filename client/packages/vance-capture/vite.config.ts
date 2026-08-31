import { cpSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { defineConfig, type Plugin } from 'vite';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Build targets.
 *
 * <p><b>The bundle is identical across them and is built once.</b> That is what
 * {@link ./src/browserApi} buys: the only engine difference we have is
 * `browser.*` versus `chrome.*`, resolved at runtime in one line, so there is
 * nothing to compile differently. Each target is the same output stamped with
 * its own manifest. If a target ever needs different *code*, that is the point
 * at which it earns its own build — not before.
 *
 * <p>Chrome is the one built into (`build.outDir`); the others are copies. That
 * is an artefact of Vite wanting a single output directory, not a statement
 * about which browser matters.
 */
type Target = 'chrome' | 'firefox';

const PRIMARY: Target = 'chrome';

/**
 * What each target adds to `manifest.base.json`.
 *
 * <p>A patch on one base rather than three hand-kept manifests: the parts that
 * must stay identical — the permissions, the popup, the options page — are
 * exactly the parts that would drift apart if they were written out three
 * times.
 */
const PATCHES: Record<Target, Record<string, unknown>> = {
  chrome: {},
  firefox: {
    // Mandatory: Firefox refuses to load an MV3 extension without an id, and
    // the failure is a flat "could not be installed" with nothing pointing at
    // the manifest.
    browser_specific_settings: {
      gecko: {
        id: 'vance-capture@vancetope.com',
        // An ESR floor rather than the oldest release that might work.
        // Picking the true minimum would mean asserting exactly when
        // `optional_host_permissions` became reliable; a version that is
        // plainly recent enough fails loudly and early instead of subtly.
        strict_min_version: '128.0',
      },
    },
  },
};

/**
 * Write each target's manifest, and copy the bundle for every target that is
 * not the one Vite built into.
 */
function emitTargets(): Plugin {
  return {
    name: 'vance-capture-targets',
    apply: 'build',
    closeBundle() {
      const base = JSON.parse(readFileSync(resolve(here, 'manifest.base.json'), 'utf8'));
      // The version comes from package.json — two places claiming the version
      // of one artefact is how a store upload ends up disagreeing with the repo.
      const pkg = JSON.parse(readFileSync(resolve(here, 'package.json'), 'utf8'));

      for (const target of Object.keys(PATCHES) as Target[]) {
        const dir = resolve(here, `dist/${target}`);
        if (target !== PRIMARY) {
          rmSync(dir, { recursive: true, force: true });
          cpSync(resolve(here, `dist/${PRIMARY}`), dir, { recursive: true });
        }
        const manifest = { ...base, version: pkg.version, ...PATCHES[target] };
        writeFileSync(
          resolve(dir, 'manifest.json'),
          `${JSON.stringify(manifest, null, 2)}\n`,
        );
      }
    },
  };
}

export default defineConfig({
  // Relative asset URLs. An extension page is served from
  // chrome-extension://<id>/, where an absolute '/assets/…' happens to work —
  // but only by coincidence of that origin's root, and it breaks the moment
  // anything is nested.
  base: './',
  // `src` as the Vite root so the two HTML entries land at the top of the
  // bundle. Built from the package root they would come out under
  // `dist/chrome/src/`, and the manifest names them without a folder.
  root: resolve(here, 'src'),
  plugins: [emitTargets()],
  build: {
    outDir: resolve(here, `dist/${PRIMARY}`),
    emptyOutDir: true,
    // No hashing. An unpacked extension is reloaded from a folder, and a
    // reviewer diffing two builds should see the code change, not a
    // filename change.
    rollupOptions: {
      input: {
        popup: resolve(here, 'src/popup.html'),
        options: resolve(here, 'src/options.html'),
      },
      output: {
        entryFileNames: 'assets/[name].js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: 'assets/[name][extname]',
      },
    },
  },
});
