import {
  RestError,
  brainFetch,
  brainFetchTextWithMeta,
  brainSendRawWithMeta,
  parseList,
  parseRecords,
  parseSheet,
  parseTree,
  serializeList,
  serializeRecords,
  serializeSheet,
  serializeTree,
} from '@vance/shared';
import { dump as dumpYaml, load as parseYaml } from 'js-yaml';
import type { AppScan } from './generated/bistromath/AppScan';
import type { RenderedView } from './generated/bistromath/RenderedView';
import { vetRestMethod, vetRestPath } from './restPolicy';

/**
 * Two surfaces, and the split is the point.
 *
 * `scanApp` / `loadView` / `rebuildApp` are *this addon's* three routes: what
 * views exist, what one looks like, re-check them. Everything a program does
 * with data goes through the **generic document API** — because every route it
 * needs already exists. A dedicated data endpoint would have put "what a row
 * is" in the backend, which never sees the app's data model anyway.
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

/**
 * One view by its own path — for a view document opened outside its app.
 *
 * <p>Same route as {@link loadView}, named differently: from the app a view has
 * a handle, from the Cortex a document has a path. The parsing is the server's
 * either way, which is the point — a second parser in the browser would be a
 * second definition of a valid view.
 */
export async function loadViewByPath(projectId: string, path: string): Promise<RenderedView> {
  return brainFetch<RenderedView>('GET', `addon/bistromath/view?${qs({ projectId, path })}`);
}

/**
 * The source of one document in the load list.
 *
 * <p>Through the addon rather than the document API, because a **bundled**
 * library has no document row — see the controller. The addon route resolves
 * the same cascade the load list was built from, so what runs is what the
 * `Loads` panel says.
 */
export async function loadScript(projectId: string, path: string): Promise<string> {
  const { text } = await brainFetchTextWithMeta(
    `addon/bistromath/script?${qs({ projectId, path })}`,
  );
  return text ?? '';
}

export async function rebuildApp(projectId: string, folder: string): Promise<AppScan> {
  return brainFetch<AppScan>('POST', `addon/bistromath/rebuild?${qs({ projectId, folder })}`);
}

// ── the open REST surface, as the program sees it ──────────────────

/**
 * One REST call on the reader's behalf: `vance.rest(method, path, body)`.
 *
 * <p><b>The host performs it, the guest never holds a credential.</b> That is
 * the difference that matters and it is not the same as handing the token in: a
 * proxied call cannot be replayed from outside the browser and cannot be posted
 * to another host, whereas a bearer token can be both, silently, for its whole
 * lifetime. The app gets the reach; the credential stays where it was.
 *
 * <p>Reach is the reader's own — the session is theirs, so the permission system
 * answers every question about what may be seen or written. Two things are
 * subtracted: the floor, which no app can re-open, and the app's own
 * declaration in its manifest, when it made one.
 *
 * <p>A failure comes back as a message with its status, because a program's
 * recovery differs by status: 404 is often an answer, 409 means read again, 403
 * means stop asking.
 */
export async function callRest(
  rawMethod: unknown,
  rawPath: unknown,
  body?: unknown,
  declared?: readonly string[] | null,
): Promise<unknown> {
  const method = vetRestMethod(rawMethod);
  const path = vetRestPath(rawPath, declared);
  try {
    return await brainFetch<unknown>(method, path, body === undefined ? {} : { body });
  } catch (e) {
    if (e instanceof RestError) {
      throw new Error(`${method} ${path} failed with ${e.status}: ${e.message}`);
    }
    throw e;
  }
}

// ── the generic document API, as the program sees it ───────────────

/** One entry of `vance.documents.list(path)`. */
export interface DocEntry {
  /** File name without extension — the key, if the folder holds records. */
  key: string;
  /**
   * Project-root path, with a leading slash — ready to hand straight back to
   * `read`.
   *
   * <p>The slash is the whole point and it was missing at first: paths a
   * program writes are relative to the **app folder**, but a listing comes back
   * from the server as a **project** path. Returning it bare meant
   * `read(entry.path)` resolved it a second time against the app folder and
   * looked for `apps/mine/apps/mine/invoices/…`. The grammar already has a
   * spelling for "from the project root", so the listing uses it.
   */
  path: string;
  title?: string;
  mime?: string;
}

interface FolderResponse {
  folders?: string[];
  files?: { id?: string; path?: string; title?: string; mimeType?: string }[];
}

interface DocSummary {
  id?: string;
  mimeType?: string;
  /** `$meta.kind`, mirrored onto the document. Decides how the body is read. */
  kind?: string;
}

/**
 * The kinds whose body is a structure rather than prose.
 *
 * <p>These four have a codec on both sides of the wire (Java in
 * `vance-shared`, TypeScript in `@vance/shared`, one shared fixture corpus
 * pinning them together). A program reading such a document gets the structure
 * — the same structure the built-in editor and the `records_*` / `sheet_*` /
 * `tree_*` / `list_*` tools work on. Which is the point: an app edits the
 * documents the rest of the system already edits.
 *
 * <p>Presentation kinds (`chart`, `diagram`, `map`, `slides`, `workflow`) are
 * deliberately absent. A program has little reason to author one, an `embed`
 * already displays it, and their codecs stay in the web host.
 */
const CODECS: Record<
  string,
  { parse: (body: string, mime: string) => unknown;
    serialize: (doc: never, mime: string) => string }
> = {
  records: { parse: parseRecords, serialize: serializeRecords as never },
  sheet: { parse: parseSheet, serialize: serializeSheet as never },
  list: { parse: parseList, serialize: serializeList as never },
  tree: { parse: parseTree, serialize: serializeTree as never },
};

/** Raised when a write lost a race. The program can catch it by name. */
export class DocumentChangedError extends Error {
  constructor(readonly path: string) {
    super(
      `'${path}' changed since it was read. Read it again before writing, ` +
        'or pass { force: true } to overwrite.',
    );
    this.name = 'DocumentChangedError';
  }
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
 * The document surface of one running app, with its **version memory**.
 *
 * <p>Every read remembers the document's `ETag`; every write sends it back as
 * `If-Match` and stores the new one from the response. So a program that reads
 * a record, changes a field and writes it back is protected from the case that
 * loses work silently — somebody else wrote in between — without the author
 * having to know that a version exists.
 *
 * <p>Implicit rather than a value the program passes around, and that is a
 * deliberate trade: it keeps read-modify-write down to two lines, at the cost
 * of a rule that has to be stated — **only a document this app has read is
 * guarded**. A blind write (no prior read) goes through unconditionally, which
 * is exactly right for creating something and exactly wrong for updating
 * something, so `write` to an unread path is the one case an author has to
 * think about.
 *
 * <p>Per instance, not global: the memory dies with the app, and two apps never
 * share one.
 */
export class DocumentAccess {
  private readonly versions = new Map<string, string>();

  constructor(private readonly projectId: string) {}

  /**
   * The documents directly inside a folder.
   *
   * <p>At most 200 — the endpoint's maximum. A larger collection needs paging
   * in the program; a table that quietly stops at 200 would be worse.
   *
   * <p>A query is **refused**, not dropped. Parameters belong to a mounted
   * document's content, not to a listing; forwarding one would be a query
   * nobody answers, dropping it would return the unfiltered folder wearing the
   * shape of a filtered one.
   */
  async list(path: string): Promise<DocEntry[]> {
    if (path.includes('?')) {
      throw new Error(
        `list('${path}') carries a query. A query parameterises the *content* of a ` +
          'mounted document, not a folder listing — read the document instead.',
      );
    }
    const res = await brainFetch<FolderResponse>(
      'GET',
      `documents/folder?${qs({ projectId: this.projectId, path, page: '0', size: '200' })}`,
    );
    const out: DocEntry[] = [];
    for (const f of res.files ?? []) {
      if (!f.path) continue;
      out.push({
        key: baseName(f.path),
        path: f.path.startsWith('/') ? f.path : `/${f.path}`,
        title: f.title,
        mime: f.mimeType,
      });
    }
    out.sort((a, b) => a.path.localeCompare(b.path));
    return out;
  }

  /**
   * A document's content — parsed for YAML and JSON, raw text otherwise.
   *
   * <p>Parsing on the host is deliberate: the host knows the mime type, and
   * shipping a parser into the sandbox to re-derive it would put the same
   * decision in two places.
   *
   * <p><b>A query goes on the content call, not on the lookup.</b>
   * `read('/_ext/demo/analysis.yaml?from=…')` is a parameterised view of a
   * mounted document; the lookup asks for the document, the content call
   * carries the parameters. Sending the whole string as a path would look for
   * a document literally named `analysis.yaml?from=…`.
   *
   * <p>`$`-prefixed keys are dropped from a parsed mapping: `$meta` is document
   * plumbing, not the record.
   */
  async read(pathWithQuery: string): Promise<unknown> {
    const { path, query } = splitQuery(pathWithQuery);
    const doc = await this.summary(path);
    const url = `documents/${encodeURIComponent(doc.id!)}/content${query ? `?${query}` : ''}`;
    const { text, response } = await brainFetchTextWithMeta(url);
    this.remember(path, response);
    return decode(text ?? '', doc.mimeType ?? '', doc.kind);
  }

  /**
   * Write a document's content, creating it when it is not there yet.
   *
   * <p>Create-or-replace, deliberately: that is what `vance.documents.write`
   * does on the server side, and a browser program that meant the same thing
   * should not need a different call. `create` exists next to it for the case
   * where "already there" is an *error* worth hearing about.
   *
   * <p>Conditional when this app has read the document before (see the class
   * comment); `force` writes regardless. A lost race raises
   * {@link DocumentChangedError} and **nothing is written**.
   *
   * <p>An object is serialised by the document's own type — YAML for a `.yaml`,
   * JSON for a `.json`. A string is written as it is, which is how a program
   * keeps full control of the bytes.
   */
  async write(path: string, content: unknown, opts: { force?: boolean } = {}): Promise<void> {
    if (path.includes('?')) {
      throw new Error(`write('${path}') carries a query. Parameters are a read-only concept.`);
    }
    const doc = await this.summaryOrNull(path);
    if (!doc) {
      // Nothing to overwrite and therefore nothing to race against — the
      // server's uniqueness check is the whole concurrency story here.
      await this.create(path, content);
      return;
    }
    const mime = doc.mimeType ?? 'text/plain';
    const headers: Record<string, string> = {};
    const known = this.versions.get(path);
    if (known && !opts.force) headers['If-Match'] = known;

    try {
      const { response } = await brainSendRawWithMeta<unknown>(
        'PUT',
        `documents/${encodeURIComponent(doc.id!)}/content`,
        encode(content, mime, doc.kind),
        `${mime}; charset=utf-8`,
        headers,
      );
      this.remember(path, response);
    } catch (e) {
      if (e instanceof RestError && e.status === 412) {
        // The version we knew is stale, and keeping it would make every retry
        // fail the same way. Forget it so a re-read can start clean.
        this.versions.delete(path);
        throw new DocumentChangedError(path);
      }
      throw e;
    }
  }

  /**
   * Create a document, and fail if one is already there.
   *
   * <p>The difference to `write` is the intent: `create` treats an existing
   * document as an error the program wants to hear about (a register that must
   * not overwrite yesterday's entry), `write` treats it as the normal case.
   */
  async create(path: string, content: unknown): Promise<void> {
    if (path.includes('?')) {
      throw new Error(`create('${path}') carries a query.`);
    }
    // Mime is left to the server, which derives it from the extension; sending
    // our own guess would be a second rule for the same question.
    //
    // No codec path here, and that is a real limit rather than an oversight: a
    // kind lives in the document's header, and there is no document yet. To
    // create a kind document, write its body as a string once — then every
    // later `read`/`write` goes through the codec.
    await brainFetch<unknown>('POST', `documents?${qs({ projectId: this.projectId })}`, {
      body: { path, inlineText: encode(content, mimeFromPath(path)) },
    });
  }

  /** Delete a document. It goes to the trash, like every other delete. */
  async delete(path: string): Promise<void> {
    const doc = await this.summary(path);
    await brainFetch<void>('DELETE', `documents/${encodeURIComponent(doc.id!)}`);
    this.versions.delete(path);
  }

  // ── plumbing ─────────────────────────────────────────────────────

  private async summary(path: string): Promise<DocSummary> {
    const doc = await this.summaryOrNull(path);
    if (!doc) throw new Error(`no document at '${path}'`);
    return doc;
  }

  /** `null` when there is no such document — a 404 is an answer here, not a fault. */
  private async summaryOrNull(path: string): Promise<DocSummary | null> {
    try {
      const doc = await brainFetch<DocSummary>(
        'GET',
        `documents/by-path?${qs({ projectId: this.projectId, path })}`,
      );
      return doc.id ? doc : null;
    } catch (e) {
      if (e instanceof RestError && e.status === 404) return null;
      throw e;
    }
  }

  private remember(path: string, response: Response): void {
    const etag = response.headers.get('ETag');
    // A mounted document has no version and must not get a remembered one — a
    // later write would then send an If-Match the server does not check.
    if (etag) this.versions.set(path, etag);
    else this.versions.delete(path);
  }
}

// ── content ────────────────────────────────────────────────────────

function mimeFromPath(path: string): string {
  if (path.endsWith('.json')) return 'application/json';
  if (path.endsWith('.yaml') || path.endsWith('.yml')) return 'application/yaml';
  if (path.endsWith('.md')) return 'text/markdown';
  if (path.endsWith('.js')) return 'text/javascript';
  return 'text/plain';
}

/**
 * Bytes → what the program sees.
 *
 * <p><b>Kind before mime.</b> A `kind: records` document is markdown on disk,
 * so reading it by mime would hand the program a wall of CSV-light text. The
 * host knows the kind — the same argument that already put mime-based parsing
 * here: the decision belongs where the knowledge is, once.
 *
 * <p>A codec that throws falls back to the mime path rather than failing the
 * read. A document whose header claims a kind its body does not match is a
 * real state (somebody hand-edited it), and the useful answer there is the raw
 * text the author can look at, not an error where the data should be.
 */
function decode(text: string, mime: string, kind?: string): unknown {
  const codec = kind ? CODECS[kind.toLowerCase()] : undefined;
  if (codec) {
    try {
      return codec.parse(text, mime);
    } catch {
      return decodeByMime(text, mime);
    }
  }
  return decodeByMime(text, mime);
}

function decodeByMime(text: string, mime: string): unknown {
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

/**
 * Turn what the program handed over into bytes.
 *
 * <p>A string is written verbatim — the program said exactly what it wanted.
 * Anything else is serialised by the document's own type, so `read` then
 * `write` round-trips through the same representation.
 */
function encode(content: unknown, mime: string, kind?: string): string {
  // A string is always taken literally, kind or no kind: the program said
  // exactly what it wanted on disk, and re-serialising it would be the host
  // overruling that.
  if (typeof content === 'string') return content;
  const codec = kind ? CODECS[kind.toLowerCase()] : undefined;
  if (codec) return codec.serialize(content as never, mime);
  if (mime.includes('json')) return `${JSON.stringify(content, null, 2)}\n`;
  return dumpYaml(content, { noRefs: true, lineWidth: 100 });
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


