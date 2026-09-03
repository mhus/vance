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
type Target = 'chrome' | 'firefox' | 'safari';

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
  // Safari reads browser_specific_settings too, and declaring the floor keeps
  // the extension from appearing in a Safari that predates MV3 — where it
  // would install and then quietly do nothing. Otherwise identical to Chrome:
  // Safari's difference is not in the manifest, it is that the whole thing has
  // to be wrapped in a native app (see scripts/safari.sh).
  safari: {
    browser_specific_settings: {
      safari: {
        strict_min_version: '16.4',
      },
    },
  },
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
        //
        // 140 rather than the previous ESR: `data_collection_permissions`
        // below arrived in 140, and an older Firefox would install the
        // extension and then silently skip the consent dialog — the one
        // place a user is told that page content leaves the browser. A floor
        // that keeps the disclosure attached is worth more than reaching
        // an ESR line that ended in 2025.
        strict_min_version: '140.0',
        // Mandatory at AMO since the Firefox 140 consent experience; an upload
        // without it fails validation outright.
        //
        // <p><b>Not `none`, and that is not a formality.</b> Mozilla defines
        // transmission as any data handled "outside the add-on or the local
        // browser", with no carve-out for a destination the user chose — so
        // the fact that the server is the user's own does not make this
        // collection-free. What actually leaves the browser is exactly two
        // things:
        //
        // <ul>
        //   <li><b>websiteContent</b> — the rendered DOM, or the file bytes
        //       for a PDF (see page.ts). That is the whole point of a grab.</li>
        //   <li><b>browsingActivity</b> — the URL and title of the page being
        //       saved. Sent on capture, and on the lookup that runs when the
        //       popup opens to say whether the page is already in the list.</li>
        // </ul>
        //
        // <p>`required`, not `optional`: an extension that may not send these
        // cannot save a page, which is the only thing it does. Nothing else is
        // declared — we collect no interaction telemetry, and the page content
        // is not mined for anything, so listing further categories would
        // overstate it in the consent dialog just as `none` would understate
        // it.
        data_collection_permissions: {
          required: ['websiteContent', 'browsingActivity'],
        },
      },
      // Firefox for Android got the key two releases later than desktop.
      // Without its own floor it inherits the desktop one, and the linter
      // rightly points out that 140 does not exist on Android.
      gecko_android: {
        strict_min_version: '142.0',
      },
    },
  },
};

/**
 * Strip the `crossorigin` attribute Vite puts on its own script and style tags.
 *
 * <p><b>Safari does not load the extension's JavaScript with it.</b> Vite adds
 * the attribute unconditionally, which is harmless on
 * `chrome-extension://` — that scheme is exempt from the CORS check. The
 * `safari-web-extension://` scheme is not: the request is made in CORS mode,
 * nothing answers with the headers, and the module never runs. The page then
 * looks *almost* right, which is the worst part — the stylesheet loads (Safari
 * is lenient there), so buttons are styled and the cursor turns into a hand,
 * and every one of them does nothing.
 *
 * <p>Removing it costs nothing anywhere: every asset here is same-origin, so
 * the attribute was only ever redundant.
 */
function stripCrossorigin(): Plugin {
  return {
    name: 'vance-capture-strip-crossorigin',
    apply: 'build',
    enforce: 'post',
    transformIndexHtml(html) {
      return html.replace(/(<(?:script|link)\b[^>]*?)\s+crossorigin(?=[\s>])/g, '$1');
    },
  };
}

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
  plugins: [stripCrossorigin(), emitTargets()],
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
