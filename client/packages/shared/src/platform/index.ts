import type { PlatformStorage } from './keyValueStore';
import type { RestConfig } from './restConfig';

export type { KeyValueStore, PlatformStorage } from './keyValueStore';
export type { AuthMode, RestConfig } from './restConfig';

interface PlatformBindings {
  storage: PlatformStorage;
  rest: RestConfig;
}

let bindings: PlatformBindings | null = null;

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
  bindings = opts;
}

function require_(): PlatformBindings {
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
  bindings = null;
}
