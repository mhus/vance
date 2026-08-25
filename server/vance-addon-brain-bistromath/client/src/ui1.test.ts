import { beforeEach, describe, expect, it, vi } from 'vitest';
import { loadLibraries, stubVance } from './testing/libraryHarness';

/**
 * `ui@1`. Asserted against a `vance.view.patch` mock, because the substance of
 * the library is **what patch it builds** — and the one thing it must never
 * build is a patch that touches behaviour.
 */
interface Ui {
  hide: (...ids: unknown[]) => Promise<unknown>;
  show: (...ids: unknown[]) => Promise<unknown>;
  toggle: (id: string, visible: boolean) => Promise<unknown>;
  label: (id: string, t: unknown) => Promise<unknown>;
  text: (id: string, t: unknown) => Promise<unknown>;
  options: (id: string, rows: unknown[], v?: string, l?: string) => Promise<unknown>;
  fieldOptions: (f: string, n: string, rows: unknown[], v?: string, l?: string) => Promise<unknown>;
  toOptions: (rows: unknown[], v?: string, l?: string) => { value: string; label: string }[];
  field: (f: string, n: string, p: unknown) => Promise<unknown>;
  fields: (f: string, p: unknown) => Promise<unknown>;
  hideFields: (f: string, n: unknown) => Promise<unknown>;
  showFields: (f: string, n: unknown) => Promise<unknown>;
  required: (f: string, n: unknown, r?: boolean) => Promise<unknown>;
  reset: (id?: string) => Promise<unknown>;
  patch: (id: string, c: unknown) => Promise<unknown>;
  list: (v: unknown) => unknown[];
}

let ui: Ui;
let patch: ReturnType<typeof vi.fn>;
let reset: ReturnType<typeof vi.fn>;

beforeEach(() => {
  patch = vi.fn().mockResolvedValue(undefined);
  reset = vi.fn().mockResolvedValue(undefined);
  ui = loadLibraries({
    sources: [{ library: 'ui@1' }],
    expose: ['ui'],
    vance: stubVance({ view: { patch, reset } }),
  }).ui as Ui;
});

describe('presence', () => {

  it('hides and shows one widget', async () => {
    await ui.hide('total');
    expect(patch).toHaveBeenCalledWith('total', { hide: true });
    await ui.show('total');
    expect(patch).toHaveBeenCalledWith('total', { hide: false });
  });

  it('takes several ids as arguments or as an array', async () => {
    await ui.hide('a', 'b');
    await ui.hide(['c', 'd']);
    expect(patch.mock.calls.map((c) => c[0])).toEqual(['a', 'b', 'c', 'd']);
  });

  it('patches all of them at once, not one round trip at a time', async () => {
    // Sequentially this is visible as a flicker; there is no ordering between
    // them to preserve.
    const resolvers: (() => void)[] = [];
    patch.mockImplementation(() => new Promise<void>((r) => resolvers.push(r)));
    const pending = ui.hide('a', 'b', 'c');
    // All three are in flight before any has answered — that is the assertion.
    expect(resolvers).toHaveLength(3);
    resolvers.forEach((r) => r());
    await pending;
  });

  it('toggle writes the branch once', async () => {
    await ui.toggle('warning', false);
    expect(patch).toHaveBeenCalledWith('warning', { hide: true });
  });
});

describe('appearance', () => {

  it('sets a label and a text', async () => {
    await ui.label('save', 'Speichern');
    expect(patch).toHaveBeenCalledWith('save', { label: 'Speichern' });
    await ui.text('note', 42);
    expect(patch).toHaveBeenCalledWith('note', { text: '42' });
  });

  it('turns null into an empty string rather than the word "null"', async () => {
    await ui.label('save', null);
    expect(patch).toHaveBeenCalledWith('save', { label: '' });
  });
});

describe('options from rows', () => {

  it('maps named fields', () => {
    expect(ui.toOptions([{ key: 'a', name: 'Acme' }, { key: 'b', name: 'Beta' }], 'key', 'name'))
      .toEqual([{ value: 'a', label: 'Acme' }, { value: 'b', label: 'Beta' }]);
  });

  it('takes plain strings as their own value and label', () => {
    expect(ui.toOptions(['open', 'paid']))
      .toEqual([{ value: 'open', label: 'open' }, { value: 'paid', label: 'paid' }]);
  });

  it('falls back through value/key/id and label/title/name', () => {
    expect(ui.toOptions([{ value: 'v', label: 'L' }])).toEqual([{ value: 'v', label: 'L' }]);
    expect(ui.toOptions([{ key: 'k', title: 'T' }])).toEqual([{ value: 'k', label: 'T' }]);
    expect(ui.toOptions([{ id: 'i', name: 'N' }])).toEqual([{ value: 'i', label: 'N' }]);
  });

  it('labels with the value when there is nothing better', () => {
    expect(ui.toOptions([{ key: 'k' }])).toEqual([{ value: 'k', label: 'k' }]);
  });

  it('drops duplicates, because a select cannot report which one was picked', () => {
    expect(ui.toOptions([{ key: 'a' }, { key: 'a' }, { key: 'b' }]))
      .toEqual([{ value: 'a', label: 'a' }, { value: 'b', label: 'b' }]);
  });

  it('skips rows with no value at all', () => {
    expect(ui.toOptions([null, undefined, { name: 'no key' }, { key: 'a' }]))
      .toEqual([{ value: 'a', label: 'a' }]);
  });

  it('stringifies a numeric value, since a choice travels as text', () => {
    expect(ui.toOptions([{ key: 1, name: 'One' }], 'key', 'name'))
      .toEqual([{ value: '1', label: 'One' }]);
  });

  it('patches a widget and a form field', async () => {
    await ui.options('status', ['open']);
    expect(patch).toHaveBeenCalledWith('status', { options: [{ value: 'open', label: 'open' }] });
    await ui.fieldOptions('f', 'status', ['open']);
    expect(patch).toHaveBeenCalledWith('f',
      { fields: { status: { options: [{ value: 'open', label: 'open' }] } } });
  });
});

describe('fields', () => {

  it('patches one and several', async () => {
    await ui.field('f', 'amount', { hide: true });
    expect(patch).toHaveBeenCalledWith('f', { fields: { amount: { hide: true } } });
    await ui.fields('f', { a: { hide: true }, b: { label: 'B' } });
    expect(patch).toHaveBeenCalledWith('f', { fields: { a: { hide: true }, b: { label: 'B' } } });
  });

  it('applies one change to a list of names in a single patch', async () => {
    await ui.hideFields('f', ['amount', 'note']);
    expect(patch).toHaveBeenCalledTimes(1);
    expect(patch).toHaveBeenCalledWith('f',
      { fields: { amount: { hide: true }, note: { hide: true } } });
  });

  it('takes one name without an array', async () => {
    await ui.showFields('f', 'amount');
    expect(patch).toHaveBeenCalledWith('f', { fields: { amount: { hide: false } } });
  });

  it('sets required, and unsets it when told', async () => {
    await ui.required('f', ['customer']);
    expect(patch).toHaveBeenCalledWith('f', { fields: { customer: { required: true } } });
    await ui.required('f', ['customer'], false);
    expect(patch).toHaveBeenCalledWith('f', { fields: { customer: { required: false } } });
  });
});

describe('boundary', () => {

  it('has no way to change behaviour', () => {
    // Appearance and presence, never behaviour: a program that could rewire
    // which function a button calls would make the document stop describing the
    // app. Asserted, not just documented.
    const names = Object.keys(ui as unknown as Record<string, unknown>);
    expect(names).not.toContain('on');
    expect(names).not.toContain('handler');
    expect(names).not.toContain('bind');
    expect(names).not.toContain('from');
  });

  it('resets one widget or all of them', async () => {
    await ui.reset('total');
    expect(reset).toHaveBeenCalledWith('total');
    await ui.reset();
    expect(reset).toHaveBeenCalledWith(undefined);
  });

  it('passes an unhelped change straight through', async () => {
    await ui.patch('x', { label: 'L', hide: false });
    expect(patch).toHaveBeenCalledWith('x', { label: 'L', hide: false });
  });
});
