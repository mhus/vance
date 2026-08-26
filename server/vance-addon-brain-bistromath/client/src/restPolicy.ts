/**
 * What `vance.rest()` may reach — the floor under every app.
 *
 * <p><b>An app may do what its reader may do.</b> That is the model, and the
 * permission system enforces it: the host performs the call with the reader's
 * session, so a project the reader cannot see stays invisible and a write they
 * may not make still fails. This file is not a second permission system.
 *
 * <p>It answers a narrower question. An app is a **document**, so the code
 * running here was written by whoever had WRITE on some project — and it runs in
 * the browser of whoever *opens* it. Those are usually the same person and
 * sometimes not, and where they differ, a click borrowed from the reader must
 * not turn into something that outlives the visit. That is the whole criterion,
 * and everything below is an instance of it.
 *
 * <p><b>A constant, deliberately not a setting.</b> The same reason
 * `secretReferenceDenyKeys` and `agentWriteDenyKeys` are properties: a boundary
 * its own subject can move is not a boundary, and the project admin who could
 * widen this list may be the author of the app it applies to. It changes when
 * the route list changes, which is a code event.
 *
 * <p><b>In the client, and that is sound here.</b> The guest has an opaque
 * origin and no cookie, so the bridge is its *only* way out — a check the guest
 * cannot route around. That the human can bypass it with devtools is beside the
 * point: they may already do all of this as themselves. This protects the
 * reader from the app, not the system from the reader.
 */

/** Prefixes no app may call, and why. Tenant-relative, matched per path segment. */
const DENIED: { prefix: string; because: string }[] = [
  // Credentials and session machinery — a stolen or dropped session is not
  // something the reader can undo by closing the tab.
  { prefix: 'access', because: 'session and credential machinery' },
  { prefix: 'refresh', because: 'session and credential machinery' },
  { prefix: 'logout', because: 'session and credential machinery' },
  { prefix: 'oauth', because: 'credentials for third-party accounts' },

  // Lasting rights, and anything that leaves the building.
  { prefix: 'admin', because: 'administration — grants, catalogues, session exports' },
  { prefix: 'share', because: 'sending content out of the house' },
  { prefix: 'mcp', because: 'executes any tool, including ones that send mail' },

  // Server-side code execution. The sharpest edge in the list: it runs in any
  // project the *reader* can reach, which is wider than the app's own.
  { prefix: 'compose', because: 'runs code on the server' },
  { prefix: 'python', because: 'runs code on the server' },
  { prefix: 'script', because: 'runs code on the server' },
  { prefix: 'scripts', because: 'runs code on the server' },
];

export class RestDeniedError extends Error {
  constructor(readonly path: string, reason: string) {
    super(`vance.rest cannot call '${path}': ${reason}`);
    this.name = 'RestDeniedError';
  }
}

/**
 * A tenant-relative path, canonical, or an error.
 *
 * <p>Canonicalising **before** matching is the whole security-relevant part:
 * `foo/../admin/x` and `.//admin//x` name the same route as `admin/x`, and a
 * prefix test against the raw string would let both past. The one bug this file
 * can have is not running this first.
 *
 * <p>The tenant is never the guest's to name — `brainFetch` supplies it — so a
 * path that tries to climb above the tenant root is refused rather than clamped:
 * it means the author expected a different grammar, and silently reinterpreting
 * it would send the call somewhere they did not ask for.
 */
export function canonicalRestPath(raw: unknown): { path: string; query: string } {
  const text = typeof raw === 'string' ? raw.trim() : '';
  if (!text) throw new RestDeniedError(String(raw ?? ''), 'no path given.');
  if (/^[a-z][a-z0-9+.-]*:/i.test(text) || text.startsWith('//')) {
    throw new RestDeniedError(
      text,
      'it is an absolute address. `vance.rest` takes a path below '
        + '/brain/{tenant}/, so it cannot lend this session to another host.',
    );
  }

  const cut = text.indexOf('?');
  const query = cut < 0 ? '' : text.slice(cut + 1);
  const rawPath = cut < 0 ? text : text.slice(0, cut);

  const out: string[] = [];
  for (const segment of rawPath.split('/')) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') {
      if (out.length === 0) {
        throw new RestDeniedError(text, 'it points above the tenant root.');
      }
      out.pop();
      continue;
    }
    out.push(segment);
  }
  if (out.length === 0) throw new RestDeniedError(text, 'no path given.');
  return { path: out.join('/'), query };
}

/**
 * Throw unless the canonical path is allowed.
 *
 * <p>Matched on the **first segment**, not by string prefix: `admin` must not
 * also catch a future `administration`, and `share` must not catch `shared`.
 * Case-insensitively, because whether a server routes `Admin` is its business
 * and not something worth depending on.
 */
export function checkRestAllowed(canonicalPath: string): void {
  const first = canonicalPath.split('/')[0].toLowerCase();
  for (const rule of DENIED) {
    if (first === rule.prefix) {
      throw new RestDeniedError(
        canonicalPath,
        `'${rule.prefix}/…' is closed to apps — ${rule.because}. An app may do what `
          + 'its reader may do, except what would outlast the visit.',
      );
    }
  }
}

/**
 * Throw unless the app declared this route family.
 *
 * <p>`undefined`/`null` means the manifest says nothing, which is unrestricted:
 * every app written before the key existed, and the only default that does not
 * break them silently. An **empty list** is a declaration — "needs no route" —
 * and closes everything, which is why the two cannot be collapsed.
 *
 * <p>Same first-segment granularity as the floor. Not a security boundary on its
 * own (the author writes both the list and the program); it is least privilege
 * by declaration, and the surface a signature will later cover.
 */
export function checkRestDeclared(
  canonicalPath: string,
  declared: readonly string[] | null | undefined,
): void {
  if (declared == null) return;
  const first = canonicalPath.split('/')[0].toLowerCase();
  if (declared.some((d) => String(d).trim().toLowerCase() === first)) return;
  throw new RestDeniedError(
    canonicalPath,
    declared.length === 0
      ? 'this app declares `rest: []` in its manifest, so it asks for no routes.'
      : `this app declares rest: [${declared.join(', ')}] in its manifest, which does `
        + `not include '${first}'. Add it there if the app genuinely needs it.`,
  );
}

/**
 * Throw unless the **tenant policy** allows this route family.
 *
 * <p>`undefined`/`null` means unrestricted — `allowed`, or no policy at all. A
 * list narrows to what it names, and an **empty list** is the meaning of
 * `restricted` with no `rest:` in the config: no REST. That is not the same as
 * useless — `vance.documents.*` is a separate host surface and stays available,
 * so a restricted app can still show and edit its own documents.
 *
 * <p>Decided by a tenant admin in `_vance/config/applications.yaml`, resolved
 * server-side, and arriving here as one answer about this app. The message says
 * who can change it, because the reader of the message usually cannot.
 */
export function checkRestPolicy(
  canonicalPath: string,
  allowed: readonly string[] | null | undefined,
): void {
  if (allowed == null) return;
  const first = canonicalPath.split('/')[0].toLowerCase();
  if (allowed.some((d) => String(d).trim().toLowerCase() === first)) return;
  throw new RestDeniedError(
    canonicalPath,
    allowed.length === 0
      ? 'this tenant restricts the app to no REST routes at all. A tenant admin '
        + 'decides that in _vance/config/applications.yaml.'
      : `this tenant allows the app only [${allowed.join(', ')}], which does not include `
        + `'${first}'. A tenant admin decides that in _vance/config/applications.yaml.`,
  );
}

/**
 * The steps in order. Returns what to hand to `brainFetch`.
 *
 * <p>Three lists, and the order is **escalating fixability**: the floor is code
 * and nobody can widen it, the policy is a tenant admin's, the declaration is
 * the app author's own. Checking them the other way round would hand a reader
 * the message they can act on least.
 */
export function vetRestPath(
  raw: unknown,
  declared?: readonly string[] | null,
  policyAllowed?: readonly string[] | null,
): string {
  const { path, query } = canonicalRestPath(raw);
  checkRestAllowed(path);
  checkRestPolicy(path, policyAllowed);
  checkRestDeclared(path, declared);
  return query ? `${path}?${query}` : path;
}

/** The methods a program may use. Anything else is a typo worth naming. */
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
export type RestMethod = (typeof METHODS)[number];

export function vetRestMethod(raw: unknown): RestMethod {
  const m = String(raw ?? 'GET').toUpperCase();
  if (!(METHODS as readonly string[]).includes(m)) {
    throw new RestDeniedError(m, `not an HTTP method. Use one of ${METHODS.join(', ')}.`);
  }
  return m as RestMethod;
}

/** For the manual and the `Loads` panel: what the floor closes off. */
export function deniedPrefixes(): string[] {
  return DENIED.map((d) => d.prefix);
}
