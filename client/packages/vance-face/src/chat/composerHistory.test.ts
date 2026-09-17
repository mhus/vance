import { describe, expect, it } from 'vitest';
import { caretInFirstLine, MAX_HISTORY_ENTRIES, pushHistoryEntry } from './composerHistory';

describe('pushHistoryEntry', () => {
  it('appends a new entry at the end (newest = last)', () => {
    expect(pushHistoryEntry(['first'], 'second')).toEqual(['first', 'second']);
  });

  it('trims the text and ignores blank input', () => {
    expect(pushHistoryEntry([], '  hello  ')).toEqual(['hello']);
    expect(pushHistoryEntry(['kept'], '   ')).toEqual(['kept']);
  });

  it('does not duplicate an immediate repeat but moves it to the newest position', () => {
    expect(pushHistoryEntry(['a', 'b'], 'b')).toEqual(['a', 'b']);
  });

  it('keeps non-consecutive repeats', () => {
    expect(pushHistoryEntry(['a', 'b'], 'a')).toEqual(['a', 'b', 'a']);
  });

  it('caps the history at MAX_HISTORY_ENTRIES, dropping the oldest', () => {
    const filled = Array.from({ length: MAX_HISTORY_ENTRIES }, (_, i) => `entry-${i}`);
    const next = pushHistoryEntry(filled, 'newest');
    expect(next).toHaveLength(MAX_HISTORY_ENTRIES);
    expect(next[0]).toBe('entry-1');
    expect(next[next.length - 1]).toBe('newest');
  });

  it('returns a copy even when nothing changes', () => {
    const entries = ['a'];
    const result = pushHistoryEntry(entries, '');
    expect(result).toEqual(entries);
    expect(result).not.toBe(entries);
  });
});

describe('caretInFirstLine', () => {
  it('is true while the caret is anywhere before the first line break', () => {
    expect(caretInFirstLine('one\ntwo', 0)).toBe(true);
    expect(caretInFirstLine('one\ntwo', 3)).toBe(true); // right before the \n
  });

  it('is false once the caret moved below line one', () => {
    expect(caretInFirstLine('one\ntwo', 4)).toBe(false);
    expect(caretInFirstLine('one\ntwo\nthree', 8)).toBe(false);
  });

  it('clamps an out-of-range selection instead of throwing', () => {
    expect(caretInFirstLine('one\ntwo', 99)).toBe(false);
    expect(caretInFirstLine('one\ntwo', -5)).toBe(true);
  });
});
