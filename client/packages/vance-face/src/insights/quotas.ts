import type { LlmBudgetStatusDto, LlmBudgetWindowDto, ProjectQuotaDto, UsageBucketDto } from '@vance/generated';

/**
 * Pure transforms behind the Quotas tab. Pulled out of {@link QuotasTab.vue}
 * so they are testable without a render — and so the same numbers can be
 * reused by a future "quotas at a glance" widget without dragging the
 * whole tab along.
 */

/**
 * Worst {@code percent} across all windows of one budget level. {@code -1}
 * when the level has no window at all (no limit set, or every window is
 * the unset default) — "unlimited" reads as a missing number, not as zero.
 */
export function highestWindowPercent(status: LlmBudgetStatusDto): number {
  let worst = -1;
  for (const w of status.windows) {
    const p = Math.round(w.percent);
    if (p > worst) worst = p;
  }
  return worst;
}

/**
 * Per-project totals for the Quotas "per-project LLM usage" section.
 *
 * <p>One project can emit several {@link UsageBucketDto} rows — one per
 * currency. We fold them into a single row so the table renders one line
 * per project; the first-seen currency wins for display, and the actual
 * amount is summed across currencies (mixed-currency totals are documented
 * in the row hint, not silently rendered under one symbol).
 *
 * <p>Rows without a {@code key} (time buckets) are skipped — they belong to
 * the summary cut, not the project cut.
 */
export interface ProjectUsageRow {
  key: string;
  currency: string;
  tokensIn: number;
  tokensOut: number;
  cacheRead: number;
  cacheWrite: number;
  costTotal: number;
  calls: number;
  callsFailed: number;
  unpricedCalls: number;
}

export function summariseProjectBuckets(buckets: UsageBucketDto[]): ProjectUsageRow[] {
  const byKey = new Map<string, ProjectUsageRow>();
  for (const b of buckets) {
    const key = b.key ?? '';
    if (!key) continue;
    let row = byKey.get(key);
    if (!row) {
      row = {
        key,
        currency: b.currency,
        tokensIn: 0,
        tokensOut: 0,
        cacheRead: 0,
        cacheWrite: 0,
        costTotal: 0,
        calls: 0,
        callsFailed: 0,
        unpricedCalls: 0,
      };
      byKey.set(key, row);
    }
    row.tokensIn += b.tokensIn;
    row.tokensOut += b.tokensOut;
    row.cacheRead += b.cacheReadTokens;
    row.cacheWrite += b.cacheWriteTokens;
    row.costTotal += b.costTotal;
    row.calls += b.calls;
    row.callsFailed += b.callsFailed;
    row.unpricedCalls += b.unpricedCalls ?? 0;
  }
  return [...byKey.values()].sort((a, b) => b.costTotal - a.costTotal);
}

/**
 * What the "Active projects" meter shows. {@code percent} is {@code null} for
 * an unlimited tenant — there is nothing to fill. Severity follows the same
 * cut-offs as the storage meter (80 % warning, full = danger); "full" is the
 * point at which the next create is refused.
 */
export interface ProjectQuotaView {
  unlimited: boolean;
  percent: number | null;
  severity: 'ok' | 'warning' | 'danger' | null;
}

export function projectQuotaView(quota: ProjectQuotaDto): ProjectQuotaView {
  if (quota.limit <= 0) return { unlimited: true, percent: null, severity: null };
  const percent = Math.round((quota.active / quota.limit) * 100);
  let severity: ProjectQuotaView['severity'] = 'ok';
  if (quota.active >= quota.limit) severity = 'danger';
  else if (percent >= 80) severity = 'warning';
  return { unlimited: false, percent, severity };
}

/** Cheap, table-friendly integer count of tokens. */
export function fmtTokens(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

/**
 * Cost formatted as {@code "1.23 USD"}. {@code n/a} when no currency is
 * supplied — a row without a price block has tokens but no amount, and
 * printing a misleading 0 would be worse than saying so.
 */
export function fmtCost(n: number, currency: string): string {
  if (!currency) return 'n/a';
  const fixed = n < 1 ? n.toFixed(4) : n.toFixed(2);
  return `${fixed} ${currency}`;
}

/**
 * Human-readable label for one budget window. {@code from} is rendered
 * verbatim because the server returns it as {@code yyyy-MM-dd} (UTC).
 */
export function windowLabel(window: LlmBudgetWindowDto, t: (k: string, params?: Record<string, unknown>) => string): string {
  switch (window.window) {
    case 'daily': return t('insights.quotas.windowDaily');
    case 'monthly': return t('insights.quotas.windowMonthly');
    default: return t('insights.quotas.windowTotal', { from: window.from });
  }
}
