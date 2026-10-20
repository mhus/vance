import { describe, expect, it } from 'vitest';
import { asOpenMode, resolveOpen } from './openMode';

describe('asOpenMode', () => {
  it('passes the three wire values through', () => {
    expect(asOpenMode('tab')).toBe('tab');
    expect(asOpenMode('same')).toBe('same');
    expect(asOpenMode('frame')).toBe('frame');
  });

  it('absent and unknown degrade to a new tab, never throw', () => {
    // A click handler is no place for a thrown TypeError: a stale bundle or
    // a hand-edited manifest must cost the mode, not the click.
    expect(asOpenMode(null)).toBe('tab');
    expect(asOpenMode(undefined)).toBe('tab');
    expect(asOpenMode('')).toBe('tab');
    expect(asOpenMode('sideways')).toBe('tab');
  });
});

describe('resolveOpen', () => {
  it('the entry override wins over the list default', () => {
    expect(resolveOpen('frame', 'tab')).toBe('frame');
    expect(resolveOpen('same', 'frame')).toBe('same');
  });

  it('without an override the list default applies', () => {
    expect(resolveOpen(null, 'frame')).toBe('frame');
    expect(resolveOpen(undefined, 'same')).toBe('same');
  });

  it('nothing set is a new tab — the behaviour the app shipped with', () => {
    expect(resolveOpen(null, null)).toBe('tab');
    expect(resolveOpen(null, undefined)).toBe('tab');
  });
});
