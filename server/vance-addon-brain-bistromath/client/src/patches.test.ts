import { describe, expect, it } from 'vitest';
import type { FormFieldDto } from '@vance/generated';
import type { ViewNode } from './generated/bistromath/ViewNode';
import { applyPatch, patchHides, patched, toOptions } from './patches';

/**
 * The reason this layer exists rather than more schema: a framework does not
 * know what its programmer needs. These tests pin the boundary of that freedom
 * — what a patch may change, and what it deliberately cannot.
 */

function field(name: string, extra: Partial<FormFieldDto> = {}): FormFieldDto {
  return {
    name,
    type: 'string',
    label: { en: name },
    required: false,
    choices: [],
    ...extra,
  } as FormFieldDto;
}

function node(over: Partial<ViewNode> = {}): ViewNode {
  return {
    type: 'form',
    label: null,
    text: null,
    from: 'rec',
    id: 'edit',
    show: null,
    columns: [],
    options: [],
    variant: null,
    mimeType: null,
    accept: null,
    fields: [],
    on: {},
    children: [],
    ...over,
  } as ViewNode;
}

describe('patched', () => {
  it('leaves an unpatched node as the very same object', () => {
    const n = node();

    // Identity, not equality: an unpatched view must cost no allocation and no
    // reactivity churn on every render.
    expect(patched(n, {})).toBe(n);
    expect(patched(node({ id: undefined }), { edit: { label: 'x' } })).toBeTruthy();
  });

  it('replaces a label and a text', () => {
    const out = patched(node({ label: 'alt', text: 'alt' }), {
      edit: { label: 'neu', text: 'neu' },
    });

    expect(out.label).toBe('neu');
    expect(out.text).toBe('neu');
  });

  it('replaces a select`s choices from either spelling', () => {
    const out = patched(node({ type: 'select' }), {
      edit: { options: ['offen', { value: 'paid', label: 'Bezahlt' }] },
    });

    expect(out.options).toEqual([
      { value: 'offen', label: 'offen' },
      { value: 'paid', label: 'Bezahlt' },
    ]);
  });

  it('hides one field of a form and keeps the rest in order', () => {
    const out = patched(node({ fields: [field('a'), field('b'), field('c')] }), {
      edit: { fields: { b: { hide: true } } },
    });

    expect(out.fields.map((f) => f.name)).toEqual(['a', 'c']);
  });

  /**
   * A patch is one string and a DTO label is a localised map, so it replaces
   * every language. Visibly total rather than quietly partial: nobody ends up
   * seeing German because only `de` was overwritten.
   */
  it('replaces a field label in every language', () => {
    const out = patched(node({ fields: [field('a', { label: { en: 'A', de: 'Ä' } })] }), {
      edit: { fields: { a: { label: 'Auftraggeber' } } },
    });

    expect(out.fields[0].label).toEqual({ en: 'Auftraggeber' });
  });

  it('changes a field`s help, requiredness and choices', () => {
    const out = patched(node({ fields: [field('a', { type: 'select' })] }), {
      edit: { fields: { a: { help: 'so', required: true, options: ['x'] } } },
    });

    expect(out.fields[0].help).toEqual({ en: 'so' });
    expect(out.fields[0].required).toBe(true);
    expect(out.fields[0].choices).toEqual([
      { value: 'x', label: { en: 'x' }, defaultSelected: false },
    ]);
  });

  /**
   * The document stays the map; a patch moves furniture. A program that could
   * rewire a handler or a binding would make the document stop describing the
   * app — so those keys are simply not in the patch type, and a patch carrying
   * them changes nothing.
   */
  it('cannot rewire a handler or a binding', () => {
    const n = node({ on: { click: { kind: 'SCRIPT', function: 'save' } } as never });

    const out = patched(n, {
      edit: { on: { click: 'evil' }, from: 'other' } as never,
    });

    expect(out.on).toEqual(n.on);
    expect(out.from).toBe('rec');
  });
});

describe('applyPatch', () => {
  it('merges a later call into an earlier one', () => {
    let map = applyPatch({}, 'edit', { label: 'eins' });
    map = applyPatch(map, 'edit', { hide: true });

    expect(map.edit).toEqual({ label: 'eins', hide: true });
  });

  it('merges field patches per field', () => {
    let map = applyPatch({}, 'edit', { fields: { a: { label: 'A' } } });
    map = applyPatch(map, 'edit', { fields: { a: { hide: true }, b: { label: 'B' } } });

    expect(map.edit.fields).toEqual({ a: { label: 'A', hide: true }, b: { label: 'B' } });
  });
});

describe('patchHides', () => {
  it('is false without an id, because a patch has nothing to name', () => {
    expect(patchHides(node({ id: undefined }), { edit: { hide: true } })).toBe(false);
  });

  it('is true when the patch says so', () => {
    expect(patchHides(node(), { edit: { hide: true } })).toBe(true);
  });
});

describe('toOptions', () => {
  it('falls back to the value when a label is empty', () => {
    expect(toOptions([{ value: 'v', label: '' }])).toEqual([{ value: 'v', label: 'v' }]);
  });
});
