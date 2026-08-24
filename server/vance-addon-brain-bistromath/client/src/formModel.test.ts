import { describe, expect, it } from 'vitest';
import type { FormFieldDto } from '@vance/generated';
import { fromFormModel, toFormModel } from './formModel';

/**
 * The one property that matters: a record that goes into a form and comes back
 * unedited must be the same record, types included. Everything a program reads
 * from a YAML document — booleans, numbers, keys no field mentions — passes
 * through here on every keystroke, so a lossy conversion is a document that
 * rots while somebody types in it.
 */

function field(name: string, type: string, extra: Partial<FormFieldDto> = {}): FormFieldDto {
  return { name, type, label: { en: name }, ...extra } as FormFieldDto;
}

/** The form's own round trip: encode, hand it straight back, decode. */
function roundTrip(
  record: Record<string, unknown>,
  fields: FormFieldDto[],
): Record<string, unknown> {
  return fromFormModel(toFormModel(record, fields), fields, record);
}

describe('form model round trip', () => {
  it('keeps a boolean a boolean and a number a number', () => {
    const fields = [field('paid', 'boolean'), field('amount', 'integer')];
    const record = { paid: true, amount: 42 };

    expect(roundTrip(record, fields)).toEqual({ paid: true, amount: 42 });
  });

  /**
   * The failure this guards against is not visible in the form: a record
   * usually carries keys no field shows, and rebuilding from the field list
   * would drop them on the first keystroke.
   */
  it('carries keys no field mentions', () => {
    const fields = [field('title', 'string')];
    const record = { title: 'Invoice', createdAt: '2026-08-25', internal: { seq: 7 } };

    expect(roundTrip(record, fields)).toEqual(record);
  });

  it('does not invent a key for a field the record never had', () => {
    const fields = [field('note', 'string'), field('title', 'string')];

    const out = roundTrip({ title: 'x' }, fields);

    expect(out).toEqual({ title: 'x' });
    expect('note' in out).toBe(false);
  });

  it('keeps an explicit null null rather than turning it into an empty string', () => {
    const fields = [field('note', 'string')];

    expect(roundTrip({ note: null }, fields)).toEqual({ note: null });
  });

  it('round-trips a repeat with its nested types', () => {
    const fields = [
      field('lines', 'repeat', {
        item: [field('sku', 'string'), field('qty', 'integer')],
      }),
    ];
    const record = { lines: [{ sku: 'A', qty: 2 }, { sku: 'B', qty: 10 }] };

    expect(roundTrip(record, fields)).toEqual(record);
  });
});

describe('editing', () => {
  it('takes what the reader typed, typed as the field says', () => {
    const fields = [field('paid', 'boolean'), field('amount', 'integer')];
    const record = { paid: false, amount: 1 };

    const out = fromFormModel({ paid: 'true', amount: '99' }, fields, record);

    expect(out).toEqual({ paid: true, amount: 99 });
  });

  /**
   * Clearing a field that held something is an intent, not an accident — so it
   * lands as an empty value rather than being ignored the way an untouched
   * empty field is.
   */
  it('lets a reader clear a field that had a value', () => {
    const fields = [field('note', 'string')];

    expect(fromFormModel({ note: '' }, fields, { note: 'old' })).toEqual({ note: '' });
  });

  /**
   * Only reachable by a paste or a hand-edit, but dropping it would lose what
   * somebody typed with no trace. The program still sees the string and can
   * refuse it.
   */
  it('keeps a non-number in a number field instead of dropping it', () => {
    const fields = [field('amount', 'integer')];

    expect(fromFormModel({ amount: 'tbd' }, fields, { amount: 5 })).toEqual({ amount: 'tbd' });
  });

  it('shows a missing record as empty fields rather than failing', () => {
    const fields = [field('title', 'string'), field('tags', 'multi_select')];

    expect(toFormModel(null, fields)).toEqual({ title: '', tags: [] });
  });

  it('starts a record from an empty form', () => {
    const fields = [field('title', 'string')];

    expect(fromFormModel({ title: 'New' }, fields, null)).toEqual({ title: 'New' });
  });
});
