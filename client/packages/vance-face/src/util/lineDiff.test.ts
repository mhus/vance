import { describe, expect, it } from 'vitest';
import { collapseContext, computeLineDiff, diffStats } from './lineDiff';

describe('computeLineDiff', () => {
  it('marks identical texts as unchanged', () => {
    const rows = computeLineDiff('a\nb\n', 'a\nb\n');
    expect(rows.map((r) => r.kind)).toEqual(['same', 'same']);
    expect(diffStats(rows)).toEqual({ added: 0, removed: 0 });
  });

  it('numbers lines on both sides', () => {
    const rows = computeLineDiff('a\nb\nc\n', 'a\nB\nc\nd\n');
    expect(rows).toEqual([
      { kind: 'same', oldNo: 1, newNo: 1, text: 'a' },
      { kind: 'del', oldNo: 2, newNo: null, text: 'b' },
      { kind: 'add', oldNo: null, newNo: 2, text: 'B' },
      { kind: 'same', oldNo: 3, newNo: 3, text: 'c' },
      { kind: 'add', oldNo: null, newNo: 4, text: 'd' },
    ]);
    expect(diffStats(rows)).toEqual({ added: 2, removed: 1 });
  });

  it('ignores a missing trailing newline and CRLF line endings', () => {
    const rows = computeLineDiff('a\r\nb', 'a\nb\n');
    expect(diffStats(rows)).toEqual({ added: 0, removed: 0 });
  });

  it('handles empty sides', () => {
    expect(computeLineDiff('', 'x\ny').map((r) => r.kind)).toEqual(['add', 'add']);
    expect(computeLineDiff('x', '').map((r) => r.kind)).toEqual(['del']);
    expect(computeLineDiff('', '')).toEqual([]);
  });
});

describe('collapseContext', () => {
  const lines = (n: number, prefix = 'l') =>
    Array.from({ length: n }, (_, i) => `${prefix}${i + 1}`).join('\n');

  it('keeps context around a change and folds the rest', () => {
    const oldText = lines(20);
    const newText = oldText.replace('l10', 'changed');
    const blocks = collapseContext(computeLineDiff(oldText, newText), 2);
    expect(blocks.map((b) => [b.type, b.rows.length])).toEqual([
      ['gap', 7],
      ['rows', 6],
      ['gap', 8],
    ]);
  });

  it('shows a one-line gap inline instead of folding it', () => {
    const oldText = lines(9);
    const newText = oldText.replace('l2', 'x').replace('l8', 'y');
    const blocks = collapseContext(computeLineDiff(oldText, newText), 2);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].type).toBe('rows');
  });

  it('folds an unchanged text into one gap', () => {
    const blocks = collapseContext(computeLineDiff(lines(5), lines(5)));
    expect(blocks.map((b) => b.type)).toEqual(['gap']);
  });
});
