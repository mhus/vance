import { brainFetch, brainFetchText } from '@vance/shared';
import { load as parseYaml } from 'js-yaml';
import type { AppScan } from './generated/bistromath/AppScan';
import type { RenderedView } from './generated/bistromath/RenderedView';

/**
 * Two surfaces, and the split is the point.
 *
 * `scanApp` / `loadView` / `rebuildApp` are *this addon's* three routes: what
 * views exist, what one looks like, re-check them. Everything else here goes
 * through the **generic document API** — because everything the program needs
 * already has a route. There is no `/table`, no `/rows`; a dedicated data
 * endpoint would have put "what a row is" in the backend, which cannot see the
 * app's data model anyway.
 */

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

// ── the app's own routes ───────────────────────────────────────────

export async function scanApp(projectId: string, folder: string): Promise<AppScan> {
  return brainFetch<AppScan>('GET', `addon/bistromath/scan?${qs({ projectId, folder })}`);
}

/** Load a view by handle, or the landing view when `handle` is omitted. */
export async function loadView(
  projectId: string,
  folder: string,
  handle?: string | null,
): Promise<RenderedView> {
  const params: Record<string, string> = { projectId, folder };
  if (handle) params.handle = handle;
  return brainFetch<RenderedView>('GET', `addon/bistromath/view?${qs(params)}`);
}

export async function rebuildApp(projectId: string, folder: string): Promise<AppScan> {
  return brainFetch<AppScan>('POST', `addon/bistromath/rebuild?${qs({ projectId, folder })}`);
}

// ── the generic document API, as the program sees it ───────────────

/** One entry of `vance.documents.list(path)`. */
export interface DocEntry {
  /** File name without extension — the key, if the folder is used as a table. */
  key: string;
  path: string;
  title?: string;
  mime?: string;
}

interface FolderResponse {
  folders?: string[];
  files?: { id?: string; path?: string; title?: string; mimeType?: string }[];
}

function baseName(path: string): string {
  const slash = path.lastIndexOf('/');
  const name = slash < 0 ? path : path.slice(slash + 1);
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(0, dot) : name;
}

/** Split `path?query` — the query belongs to the content call, not the lookup. */
export function splitQuery(pathWithQuery: string): { path: string; query: string } {
  const i = pathWithQuery.indexOf('?');
  if (i < 0) return { path: pathWithQuery, query: '' };
  return { path: pathWithQuery.slice(0, i), query: pathWithQuery.slice(i + 1) };
}

/**
 * The documents directly inside a folder.
 *
 * <p>`size` is asked for at the endpoint's maximum. A folder with more than
 * that is truncated by the server, and the program sees a short list — which is
 * why a large collection wants paging in the program rather than one call. Said
 * here because the alternative is a table that quietly stops at 200.
 *
 * <p>A query is **refused**, not dropped. Parameterised reads are a property of
 * a mounted document's content, not of a listing; forwarding it would be a
 * query nobody answers, and dropping it would return the unfiltered folder
 * wearing the shape of a filtered one.
 */
export async function listDocuments(projectId: string, path: string): Promise<DocEntry[]> {
  if (path.includes('?')) {
    throw new Error(
      `list('${path}') carries a query. A query parameterises the *content* of a ` +
        'mounted document, not a folder listing — read the document instead.',
    );
  }
  const res = await brainFetch<FolderResponse>(
    'GET',
    `documents/folder?${qs({ projectId, path, page: '0', size: '200' })}`,
  );
  const out: DocEntry[] = [];
  for (const f of res.files ?? []) {
    if (!f.path) continue;
    out.push({ key: baseName(f.path), path: f.path, title: f.title, mime: f.mimeType });
  }
  out.sort((a, b) => a.path.localeCompare(b.path));
  return out;
}

/**
 * A document's content — parsed when it is YAML or JSON, raw text otherwise.
 *
 * <p>Parsing on the host rather than in the guest is deliberate: the host knows
 * the mime type, and shipping a YAML parser into the sandbox to re-derive it
 * would put the same decision in two places. The program gets an object when
 * the document holds one, which is what makes a folder of records readable in
 * three lines.
 *
 * <p><b>A query goes on the content call, not on the lookup.</b>
 * `read('_ext/demo/analysis.yaml?from=…&to=…')` is a
 * [parameterised view](jaglan-system.md) of a mounted document: the *lookup*
 * asks for the document, and the *content* call carries the parameters, which
 * the brain forwards to the source (`MountQuery.forward`). Sending the whole
 * string as the path would look for a document literally named
 * `analysis.yaml?from=…` and find nothing.
 *
 * <p>`$`-prefixed keys are dropped from a parsed mapping: `$meta` is document
 * plumbing, not the record.
 */
export async function readDocument(projectId: string, pathWithQuery: string): Promise<unknown> {
  const { path, query } = splitQuery(pathWithQuery);
  const doc = await brainFetch<{ id?: string; mimeType?: string }>(
    'GET',
    `documents/by-path?${qs({ projectId, path })}`,
  );
  if (!doc.id) throw new Error(`no document at '${path}'`);
  const content = `documents/${encodeURIComponent(doc.id)}/content${query ? `?${query}` : ''}`;
  const text = (await brainFetchText(content)) ?? '';

  const mime = doc.mimeType ?? '';
  if (mime.includes('json')) {
    try {
      return strip(JSON.parse(text));
    } catch {
      return text;
    }
  }
  if (mime.includes('yaml') || mime.includes('yml')) {
    try {
      return strip(parseYaml(text));
    } catch {
      return text;
    }
  }
  return text;
}

function strip(value: unknown): unknown {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    if (k.startsWith('$')) continue;
    out[k] = v;
  }
  return out;
}
