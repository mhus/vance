import { describe, expect, it } from 'vitest';
import type { LlmBudgetStatusDto, LlmBudgetWindowDto, UsageBucketDto } from '@vance/generated';
import { highestWindowPercent, projectQuotaView, summariseProjectBuckets } from './quotas';

/**
 * The Quotas tab turns two server responses into the view the admin sees.
 * Pulling these pure transforms out keeps the test free of Vue's render
 * plumbing — they are what would have to be re-implemented if the
 * component ever grows another cut.
 */

function window(over: Partial<LlmBudgetWindowDto> = {}): LlmBudgetWindowDto {
  return {
    window: 'daily',
    from: '2026-01-01',
    limit: 10,
    used: 0,
    percent: 0,
    exhausted: false,
    warning: false,
    unpricedCalls: 0,
    uncounted: {},
    ...over,
  };
}

function budget(over: Partial<LlmBudgetStatusDto> = {}): LlmBudgetStatusDto {
  return {
    scope: 'tenant',
    scopeId: '_tenant',
    currency: 'USD',
    warnPercent: 80,
    totalIncomplete: false,
    windows: [],
    ...over,
  };
}

function bucket(over: Partial<UsageBucketDto> = {}): UsageBucketDto {
  return {
    bucketStart: null,
    key: null,
    currency: '',
    tokensIn: 0,
    tokensOut: 0,
    cacheReadTokens: 0,
    implicitCacheReadTokens: 0,
    cacheWriteTokens: 0,
    cacheWrite1hTokens: 0,
    costInput: 0,
    costOutput: 0,
    costCacheRead: 0,
    costCacheWrite: 0,
    costTotal: 0,
    calls: 0,
    callsFailed: 0,
    tokensInFailed: 0,
    tokensOutFailed: 0,
    unpricedCalls: 0,
    unpricedTokensIn: 0,
    unpricedTokensOut: 0,
    unmeasuredCalls: 0,
    images: 0,
    ...over,
  };
}

describe('highestWindowPercent', () => {
  it('returns -1 when no window is set', () => {
    expect(highestWindowPercent(budget())).toBe(-1);
  });

  it('returns the worst (most-loaded) window percent', () => {
    expect(highestWindowPercent(budget({
      windows: [
        window({ window: 'daily', percent: 12 }),
        window({ window: 'monthly', percent: 47 }),
      ],
    }))).toBe(47);
  });

  it('rounds fractional percents', () => {
    expect(highestWindowPercent(budget({
      windows: [window({ percent: 33.4 }), window({ percent: 33.6 })],
    }))).toBe(34);
  });
});

describe('summariseProjectBuckets', () => {
  it('returns an empty list for an empty input', () => {
    expect(summariseProjectBuckets([])).toEqual([]);
  });

  it('groups multiple currencies per project into one row', () => {
    const rows = summariseProjectBuckets([
      bucket({ key: 'demo', currency: 'USD', costTotal: 3, calls: 5, tokensIn: 100, tokensOut: 50 }),
      bucket({ key: 'demo', currency: 'EUR', costTotal: 2, calls: 4, tokensIn: 80, tokensOut: 40 }),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      key: 'demo',
      costTotal: 5,
      calls: 9,
      tokensIn: 180,
      tokensOut: 90,
      // The first-seen currency wins so a future admin eye keeps a single
      // unit per project. Mixed-currency totals are documented in the row
      // hint, not silently rendered under one symbol.
      currency: 'USD',
    });
  });

  it('orders rows by total cost, descending', () => {
    const rows = summariseProjectBuckets([
      bucket({ key: 'cheap', currency: 'USD', costTotal: 1 }),
      bucket({ key: 'loud', currency: 'USD', costTotal: 9 }),
      bucket({ key: 'mid', currency: 'USD', costTotal: 4 }),
    ]);
    expect(rows.map((r) => r.key)).toEqual(['loud', 'mid', 'cheap']);
  });

  it('skips rows without a key (time buckets)', () => {
    const rows = summariseProjectBuckets([
      bucket({ bucketStart: new Date('2026-01-01') as unknown as string, key: null, costTotal: 7 }),
      bucket({ key: 'demo', currency: 'USD', costTotal: 3 }),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0].key).toBe('demo');
  });

  it('sums cache tokens, failed calls and unpriced calls per project', () => {
    const rows = summariseProjectBuckets([
      bucket({
        key: 'demo',
        currency: 'USD',
        costTotal: 1,
        calls: 10,
        callsFailed: 2,
        cacheReadTokens: 100,
        cacheWriteTokens: 30,
        unpricedCalls: 3,
      }),
      bucket({
        key: 'demo',
        currency: 'USD',
        costTotal: 1,
        calls: 5,
        callsFailed: 1,
        cacheReadTokens: 50,
        cacheWriteTokens: 10,
        unpricedCalls: 1,
      }),
    ]);
    expect(rows[0]).toMatchObject({
      calls: 15,
      callsFailed: 3,
      cacheRead: 150,
      cacheWrite: 40,
      unpricedCalls: 4,
    });
  });
});

describe('projectQuotaView', () => {
  it('reads a zero limit as unlimited', () => {
    expect(projectQuotaView({ active: 12, limit: 0 })).toEqual({ unlimited: true, percent: null, severity: null });
  });

  it('is ok below 80 %', () => {
    expect(projectQuotaView({ active: 3, limit: 10 })).toEqual({ unlimited: false, percent: 30, severity: 'ok' });
  });

  it('warns from 80 %', () => {
    expect(projectQuotaView({ active: 8, limit: 10 }).severity).toBe('warning');
  });

  it('is full at the limit — the next create is refused', () => {
    expect(projectQuotaView({ active: 5, limit: 5 })).toEqual({ unlimited: false, percent: 100, severity: 'danger' });
  });

  it('stays full above the limit (set lower after the fact)', () => {
    expect(projectQuotaView({ active: 7, limit: 5 }).severity).toBe('danger');
  });
});
