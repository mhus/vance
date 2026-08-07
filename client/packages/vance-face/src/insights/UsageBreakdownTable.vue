<script setup lang="ts">
import type { UsageBucketDto } from '@vance/generated';

/**
 * One "top N by <dimension>" table of the usage report — project,
 * model, engine or recipe. All four cuts return the same
 * {@link UsageBucketDto} shape (one row per key × currency), so they
 * share this renderer instead of four near-identical tables.
 */
defineProps<{
  /** Column header for the key column, e.g. "Engine". */
  label: string;
  /** Rows as returned by the report endpoint, already sorted. */
  rows: UsageBucketDto[];
  /** Shown when {@link rows} is empty. */
  emptyText: string;
  /** Token formatter, owned by the parent so both stay in sync. */
  fmtTokens: (n: number) => string;
  /** Cost formatter — renders "n/a" for rows without a currency. */
  fmtCost: (n: number, currency: string) => string;
}>();
</script>

<template>
  <table class="usage-table" v-if="rows.length">
    <thead>
      <tr>
        <th>{{ label }}</th>
        <th class="num">Calls</th>
        <th class="num">Tokens in</th>
        <th class="num">Tokens out</th>
        <th class="num">Cost</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="(row, idx) in rows" :key="`${row.key}-${row.currency}-${idx}`">
        <td>{{ row.key || '—' }}</td>
        <td class="num">{{ row.calls }}</td>
        <td class="num">{{ fmtTokens(row.tokensIn) }}</td>
        <td class="num">{{ fmtTokens(row.tokensOut) }}</td>
        <td class="num">{{ fmtCost(row.costTotal, row.currency) }}</td>
      </tr>
    </tbody>
  </table>
  <p v-else class="muted">{{ emptyText }}</p>
</template>

<style scoped>
.usage-table {
  width: 100%;
  border-collapse: collapse;
  font-variant-numeric: tabular-nums;
}
.usage-table th,
.usage-table td {
  padding: 0.375rem 0.75rem;
  border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 10%, transparent);
  text-align: left;
}
.usage-table th.num,
.usage-table td.num {
  text-align: right;
}
.muted {
  color: color-mix(in oklab, var(--color-base-content) 60%, transparent);
  font-size: 0.875rem;
}
</style>
