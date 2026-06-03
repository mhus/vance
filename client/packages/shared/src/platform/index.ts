import type { PlatformStorage } from './keyValueStore';
import type { RestConfig } from './restConfig';

export type { KeyValueStore, PlatformStorage } from './keyValueStore';
export type { AuthMode, RestConfig } from './restConfig';

interface PlatformBindings {
  storage: PlatformStorage;
  rest: RestConfig;
}

// Cross-bundle shared state.
//
// `@vance/shared` is bundled into every consumer that imports it —
// the vance-face host bundle, plus a separate copy inside each
// Module-Federation addon remote. ESM module-scope variables are
// per-instance, so the host calling `configurePlatform({...})`
// configures only the host's copy; an addon's `getRestConfig()`
// reaches into its OWN copy and finds it unconfigured.
//
// `globalThis` is the one storage location every copy reaches into
// from the same browser tab / RN app. Stashing the bindings there
// turns the per-copy state into a singleton without coupling addons
// to vance-face — the addon code still just does
// `import { brainFetch } from '@vance/shared'`, no knowledge of the
// host required. configurePlatform writes; getStorage/getRestConfig
// read.
//
// Symbol.for() would also work and is even less collision-prone, but
// the well-known string key keeps debugging trivial (devtools shows
// `window.__VANCE_PLATFORM__` directly).
declare global {
  // eslint-disable-next-line no-var
  var __VANCE_PLATFORM__: PlatformBindings | null | undefined;
}

const GLOBAL_KEY = '__VANCE_PLATFORM__' as const;

function readBindings(): PlatformBindings | null {
  return globalThis[GLOBAL_KEY] ?? null;
}

function writeBindings(value: PlatformBindings | null): void {
  globalThis[GLOBAL_KEY] = value;
}

/**
 * Bind the host platform's KV stores and REST configuration once at
 * boot. Must be called before any `@vance/shared` module that touches
 * storage or makes a network request.
 *
 * Safe to call again with a different configuration (e.g. after
 * logout the host may rebind `onUnauthorized` to point at a fresh
 * navigation context); the most recent configuration wins. There is
 * no listener mechanism — callers that cached references should not,
 * and instead re-read via {@link getStorage} / {@link getRestConfig}
 * on each access.
 */
export function configurePlatform(opts: PlatformBindings): void {
  writeBindings(opts);
}

function require_(): PlatformBindings {
  const bindings = readBindings();
  if (bindings === null) {
    throw new Error(
      '@vance/shared: platform not configured — call configurePlatform({ storage, rest }) at app startup.',
    );
  }
  return bindings;
}

/**
 * Resolve the host-provided storage bindings. Throws if
 * {@link configurePlatform} has not been called yet — there is no
 * sensible default, since the choice between `localStorage` and
 * `AsyncStorage` (and their secure counterparts) is the host's
 * responsibility.
 */
export function getStorage(): PlatformStorage {
  return require_().storage;
}

/**
 * Resolve the host-provided REST configuration. Throws if
 * {@link configurePlatform} has not been called yet.
 */
export function getRestConfig(): RestConfig {
  return require_().rest;
}

/**
 * Test-only: forget the current bindings. Production code does not
 * call this — there is no use case for transitioning back to
 * "unconfigured" once an app has booted. Test suites use it between
 * cases to start each test with a fresh in-memory KV.
 */
export function __resetPlatform(): void {
  writeBindings(null);
}
