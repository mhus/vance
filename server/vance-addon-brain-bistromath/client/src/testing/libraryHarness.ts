import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Load bundled app libraries the way the runtime does, so they can be tested.
 *
 * <p>A library ships as a **resource**, not as compiled code: nothing in the
 * build would notice a typo in it, and it would fail at the first app that
 * requires it — in a browser, with a message about a function that is not
 * defined. This harness is what puts that failure into a green-or-red test
 * instead.
 *
 * <p><b>It reproduces the runtime's contract, not a convenient version of it.</b>
 * One scope, several sources joined in load order, a `vance` global, and
 * top-level `const` visible to everything after it. That is exactly what
 * `Sandbox.startAll` does (§6.3 of the spec), and it is why a library written
 * as an ES module would fail here — which is the point.
 *
 * <p>Give the files in **dependency order**: what a library requires must be
 * listed before it. The harness does not resolve `@require` — that is the
 * server's job and it has its own tests. Here the order is the assertion.
 */

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Where bundled libraries live, relative to this file.
 *
 * <p>Three levels up: `testing` → `src` → `client` → the addon root, whose
 * `src/main/resources` is the Java side's resource tree.
 */
const LIB_DIR = resolve(here, '../../../src/main/resources/vance-defaults/_vance/app-libs');

/** A source to evaluate: a bundled library by `name@version`, or literal code. */
export type LibrarySource = { library: string } | { code: string };

export interface HarnessOptions<T extends string> {
  /** In load order — a dependency before whatever needs it. */
  sources: LibrarySource[];
  /** Global names to hand back, e.g. `['core']`. */
  expose: readonly T[];
  /** The `vance.*` surface the libraries may call. Whatever the test needs. */
  vance: unknown;
}

/**
 * Evaluate the sources into one scope and return the named globals.
 *
 * @throws if a source does not parse, naming which one — the same failure a
 *     reader gets from `blameFile` at runtime.
 */
export function loadLibraries<T extends string>(
  opts: HarnessOptions<T>,
): Record<T, unknown> {
  const parts = opts.sources.map((s) => {
    if ('code' in s) return s.code;
    const path = resolve(LIB_DIR, `${s.library}.js`);
    try {
      return readFileSync(path, 'utf8');
    } catch {
      throw new Error(
        `No bundled library '${s.library}' — looked at ${path}. `
          + 'A library is named `name@version`, e.g. `core@1`.',
      );
    }
  });

  // `return { core, db }` at the end is how a top-level `const` gets out of the
  // function scope: naming them explicitly, because a `const` is not a property
  // of anything and cannot be enumerated.
  const body = `${parts.join('\n;\n')}\nreturn { ${opts.expose.join(', ')} };`;
  try {
    return new Function('vance', body)(opts.vance) as Record<T, unknown>;
  } catch (e) {
    const names = opts.sources
      .map((s) => ('code' in s ? '<inline>' : s.library))
      .join(', ');
    throw new Error(`Loading [${names}] failed: ${e instanceof Error ? e.message : String(e)}`);
  }
}

/** A `vance.*` stub whose calls a test can inspect. Fill in what the test needs. */
export function stubVance(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    state: { set: async () => {}, get: async () => undefined },
    documents: {
      list: async () => [],
      read: async () => ({}),
      write: async () => {},
      create: async () => {},
      delete: async () => {},
    },
    ui: { notify: async () => {} },
    view: { patch: async () => {}, reset: async () => {} },
    app: { folder: 'apps/test', project: 'p', tenant: 't', user: 'u', current: async () => ({}) },
    ...over,
  };
}
