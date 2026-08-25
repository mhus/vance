import { beforeEach, describe, expect, it, vi } from 'vitest';
import { loadLibraries, stubVance } from './testing/libraryHarness';

/**
 * `api@1` — the REST layer. Tested against a `vance.rest` mock, so what is
 * asserted is the **path and method it builds**, which is the whole substance of
 * the library: everything it does wrong is a request sent somewhere else.
 */
interface Api {
  call: (m: string, p: string, b?: unknown) => Promise<unknown>;
  raw: (m: string, p: string, b?: unknown) => Promise<unknown>;
  get: (p: string) => Promise<unknown>;
  post: (p: string, b?: unknown) => Promise<unknown>;
  del: (p: string) => Promise<unknown>;
  tryGet: (p: string) => Promise<unknown>;
  status: (e: unknown) => number | null;
  withProject: (p: string, id?: string) => string;
  query: (p: Record<string, unknown>) => string;
  path: (b: string, p: Record<string, unknown>) => string;
  inbox: Record<string, (...a: never[]) => Promise<unknown>>;
  documents: Record<string, (...a: never[]) => Promise<unknown>>;
}

let api: Api;
let rest: ReturnType<typeof vi.fn>;

beforeEach(() => {
  rest = vi.fn().mockResolvedValue({ ok: true });
  api = loadLibraries({
    sources: [{ library: 'api@1' }],
    expose: ['api'],
    vance: stubVance({ rest, app: { project: 'demo', tenant: 'acme' } }),
  }).api as Api;
});

describe('project parameter', () => {

  it('appends the app project when the path has none', async () => {
    await api.get('documents/folder?path=/x');
    expect(rest).toHaveBeenCalledWith('GET', 'documents/folder?path=/x&projectId=demo', undefined);
  });

  it('starts the query when there is none', () => {
    expect(api.withProject('settings')).toBe('settings?projectId=demo');
  });

  it('leaves a project the caller named alone', () => {
    // Asking about another project is legitimate — the reader's rights decide.
    expect(api.withProject('settings?projectId=other')).toBe('settings?projectId=other');
    expect(api.withProject('a?x=1&projectId=other')).toBe('a?x=1&projectId=other');
  });

  it('encodes a project name that needs it', () => {
    expect(api.withProject('settings', '_user_a.b')).toBe('settings?projectId=_user_a.b');
    expect(api.withProject('settings', 'a b')).toBe('settings?projectId=a%20b');
  });
});

describe('status', () => {

  it('reads the number the bridge attached', () => {
    const e = Object.assign(new Error('boom'), { status: 409 });
    expect(api.status(e)).toBe(409);
  });

  it('is null when the server never answered', () => {
    // A refused path or a bad argument is not a 4xx, and saying it were would
    // send a program looking for a server problem that does not exist.
    expect(api.status(new Error('closed to apps'))).toBeNull();
    expect(api.status(null)).toBeNull();
  });
});

describe('tryGet', () => {

  it('turns 404 into null, because absence is an answer', async () => {
    rest.mockRejectedValue(Object.assign(new Error('gone'), { status: 404 }));
    await expect(api.tryGet('documents/by-path?path=/nope')).resolves.toBeNull();
  });

  it('still throws on 403 — not yours is not the same as not there', async () => {
    rest.mockRejectedValue(Object.assign(new Error('nope'), { status: 403 }));
    await expect(api.tryGet('settings')).rejects.toThrow('nope');
  });

  it('still throws when there is no status at all', async () => {
    rest.mockRejectedValue(new Error('refused'));
    await expect(api.tryGet('admin/x')).rejects.toThrow('refused');
  });
});

describe('query building', () => {

  it('skips null and undefined but keeps an empty string', () => {
    // An absent filter and a filter for "" are different requests.
    expect(api.query({ a: 1, b: null, c: undefined, d: '' })).toBe('a=1&d=');
  });

  it('encodes keys and values', () => {
    expect(api.query({ 'a b': 'c&d' })).toBe('a%20b=c%26d');
  });

  it('joins onto a base that already has a query', () => {
    expect(api.path('x?a=1', { b: 2 })).toBe('x?a=1&b=2');
    expect(api.path('x', { b: 2 })).toBe('x?b=2');
    expect(api.path('x', {})).toBe('x');
  });
});

describe('named routes', () => {

  it('calls the inbox without a project — a thread has none', async () => {
    await api.inbox.list({ status: 'OPEN' } as never);
    expect(rest).toHaveBeenCalledWith('GET', 'inbox?status=OPEN', undefined);
  });

  it('posts read and archive on the thread', async () => {
    await api.inbox.read('abc' as never);
    expect(rest).toHaveBeenCalledWith('POST', 'inbox/abc/read', undefined);
    await api.inbox.archive('a/b' as never);
    expect(rest).toHaveBeenCalledWith('POST', 'inbox/a%2Fb/archive', undefined);
  });

  it('offers no inbox create, because no such route exists', () => {
    // Asserted rather than assumed: a wrapper for a missing route fails at
    // runtime having looked plausible while it was written.
    expect(api.inbox.post).toBeUndefined();
    expect(api.inbox.create).toBeUndefined();
  });

  it('lists a folder with paging defaults and the project', async () => {
    await api.documents.folder('/invoices' as never);
    expect(rest).toHaveBeenCalledWith(
      'GET', 'documents/folder?path=%2Finvoices&page=0&size=200&projectId=demo', undefined);
  });

  it('lets a caller override a paging default', async () => {
    await api.documents.folder('/x' as never, { size: 5 } as never);
    expect(rest).toHaveBeenCalledWith(
      'GET', 'documents/folder?path=%2Fx&page=0&size=5&projectId=demo', undefined);
  });
});

describe('bodies', () => {

  it('passes a body through for post', async () => {
    await api.post('inbox/x/react', { key: 'thumbsup' });
    expect(rest).toHaveBeenCalledWith('POST', 'inbox/x/react?projectId=demo', { key: 'thumbsup' });
  });

  it('sends none for delete', async () => {
    await api.del('documents/abc');
    expect(rest).toHaveBeenCalledWith('DELETE', 'documents/abc?projectId=demo', undefined);
  });
});
