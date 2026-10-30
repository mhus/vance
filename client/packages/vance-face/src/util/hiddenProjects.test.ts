import { describe, expect, it } from 'vitest';
import { isHiddenProject } from './hiddenProjects';

describe('isHiddenProject', () => {
  it('hides the tenant project on the standard surface', () => {
    expect(isHiddenProject('_tenant', 'standard')).toBe(true);
  });

  it('shows it on the expert surface', () => {
    expect(isHiddenProject('_tenant', 'expert')).toBe(false);
  });

  it('leaves every other project alone', () => {
    expect(isHiddenProject('test1', 'standard')).toBe(false);
    expect(isHiddenProject('_user_mike', 'standard')).toBe(false);
  });
});
