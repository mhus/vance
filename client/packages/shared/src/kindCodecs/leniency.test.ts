import { describe, expect, it } from 'vitest';
import { serializeList } from './listItemsCodec';
import { serializeRecords } from './recordsCodec';
import { serializeSheet } from './sheetCodec';
import { serializeTree } from './treeItemsCodec';

/**
 * The codecs have two kinds of caller and only one of them was ever tested.
 *
 * <p>A caller that **parses** a document and serialises it back always hands
 * over a complete structure — every optional collection is present, because the
 * parser put it there. That is what the shared fixture corpus exercises, and it
 * is why the corpus could not catch this: its inputs are well-formed by
 * construction.
 *
 * <p>A caller that **builds** a document hands over what it cares about. The
 * Java twins accept that (their records normalise nulls in the compact
 * constructor); the TypeScript halves used to crash on the first missing array.
 * The caller that made this concrete is a Bistromath program: read a document,
 * push a row, write it back.
 *
 * <p>Everything below is cast, on purpose. A well-typed caller cannot reach
 * these shapes — but a program in a sandbox is not type-checked, and neither is
 * an object that came across a `postMessage` boundary.
 */

describe('hand-built documents serialise', () => {
  it('records: a row with only its values', () => {
    const out = serializeRecords(
      { schema: ['name', 'age'], items: [{ values: { name: 'Alice', age: '30' } }] } as never,
      'text/markdown',
    );

    expect(out).toBe('---\nkind: records\nschema: name, age\n---\n- Alice, 30\n');
  });

  /**
   * The schema requirement is a rule of the format, not a missing default: a
   * records document without one has no columns to write values into. Leniency
   * means filling in what is *optional*, and this is not.
   */
  it('records: still refuses a document with no schema', () => {
    expect(() => serializeRecords({ items: [] } as never, 'text/markdown')).toThrow(
      /without a schema/,
    );
  });

  it('list: an item with only its text', () => {
    expect(serializeList({ items: [{ text: 'one' }] } as never, 'text/markdown')).toContain('one');
  });

  it('tree: a nested item with no children array on the leaf', () => {
    const out = serializeTree(
      { items: [{ text: 'parent', children: [{ text: 'child' }] }] } as never,
      'text/markdown',
    );

    expect(out).toContain('parent');
    expect(out).toContain('child');
  });

  it('sheet: a cell with only its address and value', () => {
    const out = serializeSheet(
      { schema: ['A'], cells: [{ field: 'A1', data: '7' }] } as never,
      'application/json',
    );

    expect(out).toContain('A1');
    expect(out).toContain('7');
  });
});
