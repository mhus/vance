import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The bundled `core@1` library, actually evaluated.
 *
 * <p>It ships as a resource, not as compiled code, so nothing else in the build
 * would ever notice a typo in it — it would fail at the first app that requires
 * it, in a browser, with a message about a function that is not defined. This
 * suite is the one thing standing between that and a green build.
 *
 * <p>Evaluated the same way the runtime does: as one script, in one scope, with
 * a `vance` global. So it also pins the load contract — a library that expected
 * a module wrapper would fail here.
 */

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(
  // From client/src up to the addon root, then into its resources.
  resolve(here, '../../src/main/resources/vance-defaults/_vance/app-libs/core@1.js'),
  'utf8',
);

interface Core {
  rows(folder: string): Promise<Record<string, unknown>[]>;
  save(folder: string, record: Record<string, unknown>, ext?: string): Promise<void>;
  remove(folder: string, key: string, ext?: string): Promise<void>;
  join(a: string, b: string): string;
  set(values: Record<string, unknown>): Promise<void>;
  get(...keys: string[]): Promise<Record<string, unknown>>;
  filter(rows: Record<string, unknown>[], needle: unknown, fields?: string[]):
    Record<string, unknown>[];
  sort(rows: Record<string, unknown>[], field: string, descending?: boolean):
    Record<string, unknown>[];
  compare(a: unknown, b: unknown): number;
  paging(rows: unknown[], pageSize?: number, previous?: { page: number }):
    { page: number; pageSize: number; totalCount: number };
  page<T>(rows: T[], paging: { page: number; pageSize: number }): T[];
  num(v: unknown, digits?: number): string;
  date(v: unknown): string;
  say(t: string): Promise<void>;
  warn(t: string): Promise<void>;
}

let core: Core;
let vance: {
  documents: {
    list: ReturnType<typeof vi.fn>;
    read: ReturnType<typeof vi.fn>;
    write: ReturnType<typeof vi.fn>;
    delete: ReturnType<typeof vi.fn>;
  };
  state: { set: ReturnType<typeof vi.fn>; get: ReturnType<typeof vi.fn> };
  ui: { notify: ReturnType<typeof vi.fn> };
};

beforeEach(() => {
  vance = {
    documents: {
      list: vi.fn(),
      read: vi.fn(),
      write: vi.fn().mockResolvedValue(undefined),
      delete: vi.fn().mockResolvedValue(undefined),
    },
    state: { set: vi.fn().mockResolvedValue(undefined), get: vi.fn() },
    ui: { notify: vi.fn().mockResolvedValue(undefined) },
  };
  // One scope, one script, a `vance` global — the runtime's contract. A `const`
  // at the top level of the library is reachable afterwards, which is exactly
  // what the joined evaluation in the sandbox provides.
  core = new Function('vance', `${source}\nreturn core;`)(vance) as Core;
});

describe('rows', () => {
  it('mixes the file name in as `key`', async () => {
    vance.documents.list.mockResolvedValue([
      { key: 'a', path: '/inv/a.yaml' },
      { key: 'b', path: '/inv/b.yaml' },
    ]);
    vance.documents.read
      .mockResolvedValueOnce({ customer: 'Acme', amount: 10 })
      .mockResolvedValueOnce({ customer: 'Zeta', amount: 20 });

    expect(await core.rows('inv/')).toEqual([
      { key: 'a', customer: 'Acme', amount: 10 },
      { key: 'b', customer: 'Zeta', amount: 20 },
    ]);
  });

  /**
   * A plain text file or YAML that did not parse comes back as a string. Losing
   * the row silently would be worse than a row that looks different, so it
   * keeps its place and says what it is.
   */
  it('keeps a non-object document as {key, text}', async () => {
    vance.documents.list.mockResolvedValue([{ key: 'note', path: '/inv/note.md' }]);
    vance.documents.read.mockResolvedValue('just prose');

    expect(await core.rows('inv/')).toEqual([{ key: 'note', text: 'just prose' }]);
  });
});

describe('save and remove', () => {
  /**
   * The key *is* the file name, so storing a copy inside the document would be
   * a second truth that can disagree with the first.
   */
  it('drops `key` from the stored body', async () => {
    await core.save('inv', { key: 'a', customer: 'Acme' });

    expect(vance.documents.write).toHaveBeenCalledWith('inv/a.yaml', { customer: 'Acme' });
  });

  it('refuses a record without a key rather than writing somewhere odd', async () => {
    await expect(core.save('inv', { customer: 'Acme' })).rejects.toThrow(/needs a record/);
    expect(vance.documents.write).not.toHaveBeenCalled();
  });

  it('deletes by key', async () => {
    await core.remove('inv/', 'a');

    expect(vance.documents.delete).toHaveBeenCalledWith('inv/a.yaml');
  });

  it('joins without doubling or dropping a slash', () => {
    expect(core.join('inv/', '/a.yaml')).toBe('inv/a.yaml');
    expect(core.join('inv', 'a.yaml')).toBe('inv/a.yaml');
  });
});

describe('state', () => {
  it('sets several keys', async () => {
    await core.set({ rows: [1], status: 'ok' });

    expect(vance.state.set).toHaveBeenCalledWith('rows', [1]);
    expect(vance.state.set).toHaveBeenCalledWith('status', 'ok');
  });

  it('reads several keys into one object', async () => {
    vance.state.get.mockImplementation((k: string) => Promise.resolve(k === 'a' ? 1 : 2));

    expect(await core.get('a', 'b')).toEqual({ a: 1, b: 2 });
  });
});

describe('filter', () => {
  const rows = [
    { key: '1', customer: 'Acme', note: 'paid' },
    { key: '2', customer: 'Zeta', note: 'open' },
  ];

  it('matches any field, case-insensitively', () => {
    expect(core.filter(rows, 'acme')).toHaveLength(1);
    expect(core.filter(rows, 'OPEN')).toHaveLength(1);
  });

  it('restricts to the named fields', () => {
    expect(core.filter(rows, 'paid', ['customer'])).toHaveLength(0);
  });

  /** A filter box nobody has typed into must not hide anything. */
  it('returns everything for an empty needle', () => {
    expect(core.filter(rows, '  ')).toHaveLength(2);
  });
});

describe('sort', () => {
  it('is numeric when both values are numbers', () => {
    const out = core.sort([{ n: '77' }, { n: '9' }], 'n');

    expect(out.map((r) => r.n)).toEqual(['9', '77']);
  });

  it('is textual otherwise', () => {
    const out = core.sort([{ n: 'b' }, { n: 'a' }], 'n');

    expect(out.map((r) => r.n)).toEqual(['a', 'b']);
  });

  /** Empty is the absence of a value, not the smallest one. */
  it('puts empty last in both directions', () => {
    expect(core.sort([{ n: '' }, { n: 2 }], 'n').map((r) => r.n)).toEqual([2, '']);
    expect(core.sort([{ n: '' }, { n: 2 }], 'n', true).map((r) => r.n)).toEqual([2, '']);
  });

  it('does not touch the array it was given', () => {
    const rows = [{ n: 2 }, { n: 1 }];
    core.sort(rows, 'n');

    expect(rows.map((r) => r.n)).toEqual([2, 1]);
  });
});

describe('paging', () => {
  const rows = Array.from({ length: 25 }, (_, i) => i);

  it('describes the list, zero-based', () => {
    expect(core.paging(rows, 10)).toEqual({ page: 0, pageSize: 10, totalCount: 25 });
  });

  it('keeps the reader on their page', () => {
    expect(core.paging(rows, 10, { page: 2 }).page).toBe(2);
  });

  /** A shrinking list must not leave the reader on a page that is gone. */
  it('clamps a page that no longer exists', () => {
    expect(core.paging(rows.slice(0, 5), 10, { page: 2 }).page).toBe(0);
  });

  it('slices what it described', () => {
    const paging = core.paging(rows, 10, { page: 2 });

    expect(core.page(rows, paging)).toEqual([20, 21, 22, 23, 24]);
  });

  /** An empty list is one empty page, not zero pages nobody can be on. */
  it('survives an empty list', () => {
    expect(core.paging([], 10)).toEqual({ page: 0, pageSize: 10, totalCount: 0 });
    expect(core.page([], { page: 0, pageSize: 10 })).toEqual([]);
  });
});

describe('formatting', () => {
  it('formats a number and refuses a non-number', () => {
    expect(core.num(1250)).toBe('1250.00');
    expect(core.num('7.5', 1)).toBe('7.5');
    expect(core.num('abc')).toBe('');
    expect(core.num(null)).toBe('');
  });

  /** Showing the raw value beats showing "Invalid Date". */
  it('hands back an unparseable date unchanged', () => {
    expect(core.date('not a date')).toBe('not a date');
    expect(core.date('')).toBe('');
    expect(core.date('2026-08-25')).not.toBe('2026-08-25');
  });
});

describe('notify', () => {
  it('says and warns with the severity at the call site', async () => {
    await core.say('hallo');
    await core.warn('achtung');

    expect(vance.ui.notify).toHaveBeenCalledWith('hallo');
    expect(vance.ui.notify).toHaveBeenCalledWith('achtung', 'warn');
  });
});
