import { describe, expect, it } from 'vitest';
import { ssoFailure } from './ssoWeb';

describe('ssoFailure', () => {
  it('keeps a rejection a rejection', () => {
    expect(ssoFailure('rejected')).toEqual({ kind: 'rejected' });
  });

  it('names a full seat quota in its own words', () => {
    expect(ssoFailure('no-seats')).toEqual({ kind: 'no-seats' });
  });

  it('reads anything else as a failed sign-in', () => {
    expect(ssoFailure('error')).toEqual({ kind: 'failed' });
    expect(ssoFailure('something-new')).toEqual({ kind: 'failed' });
  });
});
