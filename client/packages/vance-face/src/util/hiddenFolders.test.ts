import { describe, expect, it } from 'vitest';
import { isHiddenFolder } from './hiddenFolders';

describe('isHiddenFolder', () => {
  it('hides _vance at the project root on the standard surface', () => {
    expect(isHiddenFolder('', '_vance', 'standard')).toBe(true);
    expect(isHiddenFolder('/', '_vance', 'standard')).toBe(true);
  });

  it('shows everything on the expert surface', () => {
    expect(isHiddenFolder('', '_vance', 'expert')).toBe(false);
  });

  it('only filters the first level', () => {
    expect(isHiddenFolder('notes', '_vance', 'standard')).toBe(false);
  });

  it('leaves other folders alone', () => {
    expect(isHiddenFolder('', 'notes', 'standard')).toBe(false);
    expect(isHiddenFolder('', '_ext', 'standard')).toBe(false);
  });
});
