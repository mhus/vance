import type { LlmPowerStatusDto } from '@vance/generated';

/**
 * Pure helpers of the Power tab (Insights → Power). A switch the admin just
 * moved is "pending" until the answering brain pod enforces what is stored;
 * the brain names when that is due (`verifyAt`, the next refresh tick plus a
 * grace), relative to its own clock (`serverTime`), so a skewed client clock
 * does not matter.
 */

/** Key of the main switch in the pending set. */
export const TENANT_KEY = 'tenant';

/** Key of a user's switch in the pending set. */
export function userKey(userId: string): string {
  return `user:${userId}`;
}

/** Re-check interval once the first check at `verifyAt` did not converge. */
export const RETRY_MS = 2_000;

/** Checks after the first one before a switch is reported as not confirmed. */
export const MAX_RETRIES = 5;

function toMs(value: Date | string | undefined | null): number | null {
  if (value == null) return null;
  const ms = value instanceof Date ? value.getTime() : Date.parse(String(value));
  return Number.isNaN(ms) ? null : ms;
}

/** Milliseconds to wait before the first check — `verifyAt - serverTime`, never negative. */
export function verifyDelayMs(status: LlmPowerStatusDto): number {
  const at = toMs(status.verifyAt);
  const now = toMs(status.serverTime);
  if (at === null || now === null) return RETRY_MS;
  return Math.max(0, at - now);
}

/** Whether the switch behind `key` is enforced as stored by the answering pod. */
export function converged(status: LlmPowerStatusDto, key: string): boolean {
  if (key === TENANT_KEY) return status.enabled === status.effectiveEnabled;
  const user = status.users.find((u) => userKey(u.userId) === key);
  // A row that vanished has nothing left to wait for.
  return !user || user.enabled === user.effectiveEnabled;
}
