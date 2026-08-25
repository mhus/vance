import { beforeEach, describe, expect, it, vi } from 'vitest';
import { loadLibraries, stubVance } from './testing/libraryHarness';

/**
 * `db@1` — a folder as a table, loaded **on top of `core@1`** because that is
 * how the runtime loads it: two sources, one scope, dependency first. So this
 * file also asserts that a library requiring a library works.
 *
 * <p>The document layer is a small fake rather than a mock per call: what is
 * being tested is the table's bookkeeping — what it caches, what it refuses,
 * what it writes — and that only shows against something that behaves like
 * storage.
 */
interface Table {
  all: () => Promise<Record<string, unknown>[]>;
  reload: () => Promise<Record<string, unknown>[]>;
  get: (k: string) => Promise<Record<string, unknown> | null>;
  where: (m: unknown) => Promise<Record<string, unknown>[]>;
  first: (m: unknown) => Promise<Record<string, unknown> | null>;
  count: (m?: unknown) => Promise<number>;
  insert: (r: Record<string, unknown>) => Promise<Record<string, unknown>>;
  update: (k: string, p: Record<string, unknown>) => Promise<Record<string, unknown>>;
  upsert: (r: Record<string, unknown>) => Promise<Record<string, unknown>>;
  remove: (k: string) => Promise<void>;
  nextKey: (rows?: Record<string, unknown>[]) => string;
}
interface Db { table: (folder: string, opts?: Record<string, unknown>) => Table }

/** Documents as a map of path → content, with the calls counted. */
function fakeStore(initial: Record<string, unknown> = {}) {
  const store = new Map<string, unknown>(Object.entries(initial));
  const reads = vi.fn();
  return {
    store,
    reads,
    documents: {
      list: async (folder: string) => [...store.keys()]
        .filter((p) => p.startsWith(folder))
        .map((p) => ({ key: p.slice(folder.length).replace(/\.[^.]+$/, ''), path: p })),
      read: async (path: string) => {
        reads(path);
        return store.get(path);
      },
      write: async (path: string, content: unknown) => { store.set(path, content); },
      delete: async (path: string) => { store.delete(path); },
    },
  };
}

let db: Db;
let fake: ReturnType<typeof fakeStore>;

function build(initial: Record<string, unknown> = {}) {
  fake = fakeStore(initial);
  db = loadLibraries({
    sources: [{ library: 'core@1' }, { library: 'db@1' }],
    expose: ['db'],
    vance: stubVance({ documents: fake.documents }),
  }).db as Db;
}

beforeEach(() => build({
  'inv/0001.yaml': { customer: 'Acme', amount: 100, status: 'open' },
  'inv/0002.yaml': { customer: 'Beta', amount: 20, status: 'paid' },
}));

describe('reading', () => {

  it('mixes the key in and keeps the body', async () => {
    const rows = await db.table('inv/').all();
    expect(rows).toEqual([
      { key: '0001', customer: 'Acme', amount: 100, status: 'open' },
      { key: '0002', customer: 'Beta', amount: 20, status: 'paid' },
    ]);
  });

  it('reads the folder once, however many questions are asked', async () => {
    const t = db.table('inv/');
    await t.all();
    await t.where({ status: 'open' });
    await t.count();
    await t.get('0002');
    // Two documents, one pass. Without the cache this is four passes — one
    // request per document per question.
    expect(fake.reads).toHaveBeenCalledTimes(2);
  });

  it('reads nothing until asked', () => {
    db.table('inv/');
    expect(fake.reads).not.toHaveBeenCalled();
  });

  it('reload picks up a change somebody else made', async () => {
    const t = db.table('inv/');
    await t.all();
    fake.store.set('inv/0003.yaml', { customer: 'Gamma' });
    expect(await t.count()).toBe(2);
    expect((await t.reload()).length).toBe(3);
  });

  it('answers a missing key with null rather than throwing', async () => {
    expect(await db.table('inv/').get('nope')).toBeNull();
  });

  it('takes a match object or a predicate', async () => {
    const t = db.table('inv/');
    expect(await t.where({ status: 'open' })).toHaveLength(1);
    expect(await t.where((r: Record<string, unknown>) => Number(r.amount) > 50)).toHaveLength(1);
    expect(await t.count({ status: 'paid' })).toBe(1);
    expect((await t.first({ customer: 'Beta' }))?.key).toBe('0002');
    expect(await t.first({ customer: 'Nobody' })).toBeNull();
  });
});

describe('insert', () => {

  it('generates the next key, padded to the width in use', async () => {
    const rec = await db.table('inv/').insert({ customer: 'Gamma' });
    expect(rec.key).toBe('0003');
    expect(fake.store.get('inv/0003.yaml')).toEqual({ customer: 'Gamma' });
  });

  it('does not store the key inside the document', async () => {
    await db.table('inv/').insert({ key: 'x1', customer: 'Delta' });
    expect(fake.store.get('inv/x1.yaml')).toEqual({ customer: 'Delta' });
  });

  it('refuses an existing key instead of overwriting', async () => {
    await expect(db.table('inv/').insert({ key: '0001', customer: 'Other' }))
      .rejects.toThrow(/already exists/);
    expect(fake.store.get('inv/0001.yaml')).toMatchObject({ customer: 'Acme' });
  });

  it('keeps the cache in step, so the next read needs no round trip', async () => {
    const t = db.table('inv/');
    await t.insert({ customer: 'Gamma' });
    fake.reads.mockClear();
    expect(await t.count()).toBe(3);
    expect(fake.reads).not.toHaveBeenCalled();
  });

  it('starts at 1 in an empty folder', async () => {
    build();
    expect((await db.table('inv/').insert({ a: 1 })).key).toBe('1');
  });
});

describe('nextKey', () => {

  it('is max + 1, not the row count', async () => {
    // Counting rows collides the moment the last record is deleted.
    build({ 'inv/0007.yaml': { a: 1 } });
    const t = db.table('inv/');
    await t.all();
    expect(t.nextKey()).toBe('0008');
  });

  it('ignores keys that are not numbers', async () => {
    build({ 'inv/draft.yaml': { a: 1 }, 'inv/0003.yaml': { a: 2 } });
    const t = db.table('inv/');
    await t.all();
    expect(t.nextKey()).toBe('0004');
  });

  it('honours a prefix and only counts keys that carry it', async () => {
    build({ 'inv/inv-5.yaml': { a: 1 }, 'inv/9.yaml': { a: 2 } });
    const t = db.table('inv/', { keyPrefix: 'inv-' });
    await t.all();
    expect(t.nextKey()).toBe('inv-6');
  });

  it('takes rows, so a loop can insert without re-reading', async () => {
    const t = db.table('inv/');
    const rows = await t.all();
    expect(t.nextKey(rows)).toBe('0003');
  });

  it('says what to do when nothing has been read yet', () => {
    expect(() => db.table('inv/').nextKey()).toThrow(/before this/);
  });
});

describe('update, upsert, remove', () => {

  it('merges a patch and keeps the rest', async () => {
    const merged = await db.table('inv/').update('0001', { status: 'paid' });
    expect(merged).toEqual({ key: '0001', customer: 'Acme', amount: 100, status: 'paid' });
    expect(fake.store.get('inv/0001.yaml')).toEqual({
      customer: 'Acme', amount: 100, status: 'paid',
    });
  });

  it('refuses an unknown key rather than creating one', async () => {
    // An update that silently creates turns a typo into a second, nearly
    // identical record.
    await expect(db.table('inv/').update('0009', { a: 1 })).rejects.toThrow(/no record/);
    expect(fake.store.has('inv/0009.yaml')).toBe(false);
  });

  it('cannot be talked into moving a record by patching its key', async () => {
    const merged = await db.table('inv/').update('0001', { key: 'elsewhere' });
    expect(merged.key).toBe('0001');
    expect(fake.store.has('inv/elsewhere.yaml')).toBe(false);
  });

  it('upsert replaces the whole record, unlike update', async () => {
    const t = db.table('inv/');
    await t.upsert({ key: '0001', customer: 'Acme' });
    expect(fake.store.get('inv/0001.yaml')).toEqual({ customer: 'Acme' });
    expect((await t.get('0001'))?.amount).toBeUndefined();
  });

  it('upsert creates when absent', async () => {
    const t = db.table('inv/');
    await t.upsert({ key: 'new', a: 1 });
    expect(await t.count()).toBe(3);
  });

  it('remove drops it from storage and from the cache', async () => {
    const t = db.table('inv/');
    await t.all();
    await t.remove('0001');
    expect(fake.store.has('inv/0001.yaml')).toBe(false);
    fake.reads.mockClear();
    expect(await t.count()).toBe(1);
    expect(fake.reads).not.toHaveBeenCalled();
  });
});

describe('extension', () => {

  it('writes .yaml by default and honours an override', async () => {
    await db.table('inv/').insert({ key: 'a', x: 1 });
    expect(fake.store.has('inv/a.yaml')).toBe(true);
    await db.table('notes/', { extension: '.md' }).insert({ key: 'b', x: 1 });
    expect(fake.store.has('notes/b.md')).toBe(true);
  });
});

describe('folder grammar', () => {

  it('refuses a folder that repeats the app folder', () => {
    // It would otherwise *work* -- writes and reads double identically -- and
    // put every record in apps/mine/apps/mine/..., where nobody looks. Found in
    // the browser, from a line that reads perfectly: vance.app.folder + '/x/'.
    build();
    db = loadLibraries({
      sources: [{ library: 'core@1' }, { library: 'db@1' }],
      expose: ['db'],
      vance: stubVance({ documents: fake.documents, app: { folder: 'apps/mine' } }),
    }).db as Db;

    expect(() => db.table('apps/mine/records/')).toThrow(/repeats the app folder/);
    expect(() => db.table('apps/mine/records/')).toThrow(/use 'records\/'/);
    expect(() => db.table('./apps/mine/records/')).toThrow(/repeats the app folder/);
  });

  it('allows the same path from the project root', () => {
    build();
    db = loadLibraries({
      sources: [{ library: 'core@1' }, { library: 'db@1' }],
      expose: ['db'],
      vance: stubVance({ documents: fake.documents, app: { folder: 'apps/mine' } }),
    }).db as Db;

    // A leading slash says "project root", so it is not a doubling.
    expect(() => db.table('/apps/mine/records/')).not.toThrow();
    expect(() => db.table('records/')).not.toThrow();
    // A folder that merely starts with the same letters is not the app folder.
    expect(() => db.table('apps/mine-other/records/')).not.toThrow();
  });

  it('needs a folder at all', () => {
    expect(() => db.table('')).toThrow(/needs a folder/);
  });
});
