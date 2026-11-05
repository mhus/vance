import { describe, expect, it } from 'vitest';
import {
  buildParams, fieldTypeOf, seedInputs,
  type ParamInput, type StartParamSpec,
} from './startParams';

const spec = (over: Partial<StartParamSpec> & { name: string }): StartParamSpec => ({
  type: 'string', required: false, ...over,
});
const text = (value: string): ParamInput => ({ text: value, checked: false });

describe('fieldTypeOf', () => {
  it('maps declared types to form fields', () => {
    expect(fieldTypeOf(spec({ name: 'a', type: 'integer' }))).toBe('integer');
    expect(fieldTypeOf(spec({ name: 'a', type: 'boolean' }))).toBe('boolean');
    expect(fieldTypeOf(spec({ name: 'a', type: 'object' }))).toBe('json');
    expect(fieldTypeOf(spec({ name: 'a', type: 'array' }))).toBe('json');
    expect(fieldTypeOf(spec({ name: 'a', type: 'string' }))).toBe('text');
    expect(fieldTypeOf(spec({ name: 'a', type: 'something-else' }))).toBe('text');
  });
});

describe('seedInputs', () => {
  it('renders defaults as the text a user would type', () => {
    const seeded = seedInputs([
      spec({ name: 's', defaultValue: 'hello' }),
      spec({ name: 'n', type: 'integer', defaultValue: 3 }),
      spec({ name: 'o', type: 'object', defaultValue: { a: 1 } }),
      spec({ name: 'b', type: 'boolean', defaultValue: true }),
      spec({ name: 'none' }),
    ]);
    expect(seeded.s.text).toBe('hello');
    expect(seeded.n.text).toBe('3');
    expect(seeded.o.text).toBe('{"a":1}');
    expect(seeded.b.checked).toBe(true);
    expect(seeded.none.text).toBe('');
  });
});

describe('buildParams', () => {
  it('converts each value by its declared type', () => {
    const result = buildParams(
      [
        spec({ name: 's' }),
        spec({ name: 'n', type: 'integer' }),
        spec({ name: 'o', type: 'object' }),
        spec({ name: 'b', type: 'boolean' }),
      ],
      { s: text(' hi '), n: text('42'), o: text('{"x":[1,2]}'), b: { text: '', checked: true } },
    );
    expect(result).toEqual({ ok: true, params: { s: 'hi', n: 42, o: { x: [1, 2] }, b: true } });
  });

  it('leaves an empty optional field out, so the server default applies', () => {
    const result = buildParams([spec({ name: 's', defaultValue: 'd' })], { s: text('  ') });
    expect(result).toEqual({ ok: true, params: {} });
  });

  it('refuses an empty required field that has no default', () => {
    const result = buildParams([spec({ name: 's', required: true })], { s: text('') });
    expect(result).toEqual({ ok: false, issue: { code: 'required', key: 's' } });
  });

  it('accepts an empty required field that has a default', () => {
    const result = buildParams(
      [spec({ name: 's', required: true, defaultValue: 'd' })], { s: text('') });
    expect(result).toEqual({ ok: true, params: {} });
  });

  it('refuses an integer with trailing text instead of reading its prefix', () => {
    const result = buildParams([spec({ name: 'n', type: 'integer' })], { n: text('12abc') });
    expect(result).toEqual({ ok: false, issue: { code: 'notInteger', key: 'n', raw: '12abc' } });
  });

  it('refuses a decimal where an integer is declared', () => {
    const result = buildParams([spec({ name: 'n', type: 'integer' })], { n: text('1.5') });
    expect(result.ok).toBe(false);
  });

  it('refuses invalid JSON for an object', () => {
    const result = buildParams([spec({ name: 'o', type: 'object' })], { o: text('{nope') });
    expect(result).toMatchObject({ ok: false, issue: { code: 'notJson', key: 'o' } });
  });

  it('sends an unchecked boolean as false', () => {
    const result = buildParams([spec({ name: 'b', type: 'boolean' })], {});
    expect(result).toEqual({ ok: true, params: { b: false } });
  });
});
