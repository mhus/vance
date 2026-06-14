/**
 * Runtime configuration loader. Fetches `./config.json` (relative
 * path so it works under any base path) at first call, caches the
 * result for the lifetime of the page.
 *
 * The file is committed in `vance-face/public/config.json` as a
 * dev/local default; the production docker entrypoint overwrites
 * it with env-injected values at pod start so the docker image
 * stays deployment-agnostic. See
 * `deployment/docker/face/docker-entrypoint.sh` and
 * `planning/vance-facelift-share-extension.md` for the wider
 * pattern.
 */

export interface RuntimeConfig {
  /** Always `"vance"` — sanity-check for the iOS Facelift wrapper
   *  before saving an account URL. */
  product: string;
  /** Bumped by us when the shape changes incompatibly. v1 = 1. */
  schema: number;
  /** Build version of the deployed `vance-face` bundle. */
  version?: string;
  /** Free-form deployment label (e.g. {@code "production"},
   *  {@code "staging"}, {@code "mini"}). */
  deployment?: string;
  /** Pod / container hostname — useful for debugging which pod the
   *  user hit when the deployment runs multiple replicas. */
  hostname?: string;
  /** Git SHA of the build, when known. */
  buildSha?: string;
  /** Human-friendly server label shown under the "Vance" brand on
   *  the login screen and in the iOS Facelift wrapper's account
   *  picker. Empty / missing = no label. */
  title?: string;
  /** Optional URL back to the operating organisation's home page.
   *  Rendered as a small link below the title on the login screen.
   *  Empty / missing = no link. */
  backlink?: string;
}

const FALLBACK: RuntimeConfig = {
  product: 'vance',
  schema: 1,
  version: 'unknown',
  deployment: 'unknown',
  title: '',
  backlink: '',
};

let cached: RuntimeConfig | null = null;
let inflight: Promise<RuntimeConfig> | null = null;

/**
 * Resolve the runtime config. Returns a cached value on subsequent
 * calls within the same page lifetime. Returns the fallback shape
 * when the fetch fails (e.g. dev server without docker, or the
 * static file was removed) so callers never have to null-check the
 * top-level shape — only individual optional fields.
 */
export async function loadRuntimeConfig(): Promise<RuntimeConfig> {
  if (cached !== null) return cached;
  if (inflight !== null) return inflight;
  inflight = (async () => {
    try {
      const res = await fetch('./config.json', { cache: 'no-store' });
      if (!res.ok) return FALLBACK;
      const parsed = (await res.json()) as RuntimeConfig;
      if (parsed.product !== 'vance') return FALLBACK;
      return parsed;
    } catch {
      return FALLBACK;
    }
  })().then((c) => {
    cached = c;
    inflight = null;
    return c;
  });
  return inflight;
}

/** Synchronous access to the cached config — null until
 *  {@link loadRuntimeConfig} has resolved at least once. */
export function runtimeConfig(): RuntimeConfig | null {
  return cached;
}
