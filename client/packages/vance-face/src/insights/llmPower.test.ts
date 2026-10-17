import { describe, expect, it } from 'vitest';
import type { LlmPowerStatusDto } from '@vance/generated';
import { converged, RETRY_MS, TENANT_KEY, userKey, verifyDelayMs } from './llmPower';

function status(patch: Partial<LlmPowerStatusDto> = {}): LlmPowerStatusDto {
  return { enabled: true, effectiveEnabled: true, users: [], ...patch };
}

describe('verifyDelayMs', () => {
  it('waits from the server clock to verifyAt, not from the client clock', () => {
    const s = status({
      serverTime: '2026-10-17T10:00:17Z' as unknown as Date,
      verifyAt: '2026-10-17T10:01:02Z' as unknown as Date,
    });
    expect(verifyDelayMs(s)).toBe(45_000);
  });

  it('falls back to the retry interval without timestamps', () => {
    expect(verifyDelayMs(status())).toBe(RETRY_MS);
  });
});

describe('converged', () => {
  it('compares stored and enforced main switch', () => {
    expect(converged(status({ enabled: false, effectiveEnabled: true }), TENANT_KEY)).toBe(false);
    expect(converged(status({ enabled: false, effectiveEnabled: false }), TENANT_KEY)).toBe(true);
  });

  it('compares stored and enforced user switch', () => {
    const s = status({
      users: [{ userId: 'road.runner', serviceAccount: false, known: true, enabled: false, effectiveEnabled: true }],
    });
    expect(converged(s, userKey('road.runner'))).toBe(false);
    expect(converged(s, userKey('gone'))).toBe(true);
  });
});
