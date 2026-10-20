<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type {
  LlmBudgetStatusDto,
  LlmBudgetWindowDto,
  StorageCleanupResultDto,
} from '@vance/generated';
import { VAlert, VBadge, VButton, VCard, VEmptyState, VGroup, VGroupRow, VIcon, useConfirm } from '@/components';
import { useHomeStatus } from '@/composables/useHomeStatus';
import {
  INJECT_INSIGHTS_PROJECT_SCOPE,
} from '@/composables/useInsightsNavigation';
import { useStorageCleanup } from '@/composables/useStorageCleanup';
import { useUsageReport } from '@/composables/useUsageReport';
import CleanupDialog from './CleanupDialog.vue';
import {
  fmtCost,
  fmtTokens,
  summariseProjectBuckets,
  type ProjectUsageRow,
  windowLabel as windowLabelOf,
} from './quotas';

/**
 * Insights → Quotas: tenant-wide overview of the two quota systems.
 *
 * <p>Three sections, each answering one question an admin asks when sizing
 * a tenant:
 * <ol>
 *   <li><b>Documents</b> — storage quota + bytes used. Tenant-level only
 *       (per planning/storage-quota.md, no per-project storage quota).</li>
 *   <li><b>LLM</b> — the cost budget (limits) plus the most-loaded window
 *       as a percent. Tenant and every project side-by-side. LLM power
 *       switch surfaced here too: it is the third way to stop spend.</li>
 *   <li><b>Per-project LLM usage</b> — cost, calls and tokens per project
 *       over the chosen window, so the tenant admin sees who is burning
 *       the budget, not just whether it is burning.</li>
 * </ol>
 *
 * <p>Endpoints used (all already shipped — see
 * {@code HomeStatusController}, {@code LlmUsageReportController}):
 * <ul>
 *   <li>{@code GET /brain/{tenant}/home/status} — available to every
 *       signed-in member; powers section 1 and the power-switch line in
 *       section 2.</li>
 *   <li>{@code GET /brain/{tenant}/usage/budget} — admin; powers the
 *       tenant + project budget list in section 2.</li>
 *   <li>{@code GET /brain/{tenant}/usage/by-project} — admin; powers
 *       section 3.</li>
 * </ul>
 *
 * <p>A non-admin who lands here sees section 1 and a friendly
 * "admin-only" notice where sections 2 and 3 would be — same
 * fail-open philosophy as the rest of the landing-page status hints.
 */
const { t } = useI18n();
const home = useHomeStatus();
const usage = useUsageReport();
const cleanup = useStorageCleanup();
const confirmAction = useConfirm();

/**
 * Project scope comes from the sidebar (InsightsApp.filterProjectId), not
 * from a per-tab dropdown — the sidebar already has the "Whole tenant vs
 * one project" selector and every project-scoped tab reads the same value
 * via provide/inject. {@code null} / empty string = whole tenant.
 */
const projectScope = inject(
    INJECT_INSIGHTS_PROJECT_SCOPE,
    ref<string | null>(null),
);

const cleanupDialogOpen = ref(false);
const cleanupBusy = ref(false);
const lastCleanup = ref<{ action: string; freedBytes: number } | null>(null);

/** Normalised scope for the cleanup calls: {@code null} when the sidebar says tenant-wide. */
const cleanupProjectId = computed<string | null>(() => {
  const v = projectScope.value;
  return v && v.trim().length > 0 ? v : null;
});

const cleanupError = computed<string | null>(() => cleanup.error.value);
const cleanupLoading = computed(() => cleanup.loading.value);

async function refreshCleanup(): Promise<void> {
  await cleanup.loadBreakdown(cleanupProjectId.value);
}

watch(projectScope, () => {
  void refreshCleanup();
});

async function onEmptyTrash(): Promise<void> {
  const breakdown = cleanup.breakdown.value;
  if (!breakdown || breakdown.trashBytes === 0) return;
  if (!await confirmAction({
    message: t('insights.quotas.emptyTrashConfirm', {
      bytes: formatBytesShort(breakdown.trashBytes),
      count: breakdown.trashCount,
    }),
    confirmLabel: t('insights.quotas.emptyTrashConfirmLabel'),
    danger: true,
  })) return;
  cleanupBusy.value = true;
  try {
    const result = await cleanup.emptyTrash(cleanupProjectId.value);
    lastCleanup.value = { action: 'trash', freedBytes: result.freedBytes };
    // The breakdown has already been refreshed inside the composable.
    await refreshHomeQuota();
  } finally {
    cleanupBusy.value = false;
  }
}

function onOpenArchiveDialog(): void {
  cleanupDialogOpen.value = true;
}

async function onArchiveDone(result: StorageCleanupResultDto): Promise<void> {
  lastCleanup.value = { action: 'archives', freedBytes: result.freedBytes };
  await refreshHomeQuota();
}

/** Tiny byte formatter for the inline preview line — keeps the card compact. */
function formatBytesShort(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes < 1024 * 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
  return `${(bytes / 1024 / 1024 / 1024 / 1024).toFixed(2)} TB`;
}

const rangeDays = ref<number>(30);

const loading = computed(() => home.loading.value || usage.loading.value);
const error = computed<string | null>(() => home.error.value ?? usage.error.value);

/** True when the usage endpoints answered — proxy for "caller is tenant admin". */
const isAdmin = computed(() => usage.byProject.value !== null || usage.budget.value !== null);
const adminMissing = computed(
  () => !loading.value && !isAdmin.value && home.status.value !== null,
);

async function refresh(): Promise<void> {
  await Promise.allSettled([
    home.load(null),
    usage.loadAll(buildParams()),
    cleanup.loadBreakdown(cleanupProjectId.value),
  ]);
}

/**
 * Reload the home quota after a cleanup so the top-of-card meter reflects
 * the freed bytes. Cleanup endpoint already invalidated the local cache,
 * but the home/status response also carries bytes-free — re-read so the
 * two numbers in this tab agree.
 */
async function refreshHomeQuota(): Promise<void> {
  await home.load(null);
}

function buildParams(): { from: string; to: string; groupBy: 'day' } {
  const to = new Date();
  const from = new Date(to.getTime() - rangeDays.value * 24 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: to.toISOString(), groupBy: 'day' };
}

onMounted(refresh);

// ── Storage section ──────────────────────────────────────────────────────
const storagePercent = computed<number | null>(() => {
  const v = home.status.value?.storageQuotaPercent;
  return v == null ? null : v;
});
const storageUnlimited = computed<boolean>(
  () => storagePercent.value != null && storagePercent.value < 0,
);

/** Severity from the percent, if a quota is configured. */
const storageSeverity = computed<'ok' | 'warning' | 'danger' | null>(() => {
  if (storagePercent.value == null || storagePercent.value < 0) return null;
  if (storagePercent.value >= 100) return 'danger';
  if (storagePercent.value >= 80) return 'warning';
  return 'ok';
});

function storageRowLabel(): string {
  if (storageUnlimited.value) return t('insights.quotas.storageUnlimited');
  if (storagePercent.value == null) return t('insights.quotas.storageUnknown');
  return t('insights.quotas.storagePercent', { percent: storagePercent.value });
}

// ── LLM section ──────────────────────────────────────────────────────────
const llmPowerOn = computed<boolean | null>(() => home.status.value?.llmPowerOn ?? null);

const tenantBudget = computed<LlmBudgetStatusDto | null>(
  () => usage.budget.value?.find((b) => b.scope === 'tenant') ?? null,
);
const projectBudgets = computed<LlmBudgetStatusDto[]>(
  () => (usage.budget.value ?? []).filter((b) => b.scope === 'project'),
);

function budgetCurrency(status: LlmBudgetStatusDto): string {
  return status.currency || '';
}

function windowLabel(window: LlmBudgetWindowDto): string {
  // windowLabelOf accepts a string-returning callback; vue-i18n's t
  // overload requires Record<string, unknown> for params, so we wrap it
  // and forward only the keys this helper actually uses.
  if (window.window === 'daily') return t('insights.quotas.windowDaily');
  if (window.window === 'monthly') return t('insights.quotas.windowMonthly');
  return windowLabelOf(window, t);
}

function windowValue(window: LlmBudgetWindowDto, currency: string): string {
  return t('insights.quotas.windowValue', {
    used: fmtCost(window.used, currency),
    limit: fmtCost(window.limit, currency),
    pct: Math.round(window.percent),
  });
}

function windowGaps(window: LlmBudgetWindowDto): string {
  const parts: string[] = [];
  if (window.unpricedCalls > 0) {
    parts.push(t('insights.quotas.unpricedCalls', { calls: window.unpricedCalls }));
  }
  for (const [cur, amount] of Object.entries(window.uncounted ?? {})) {
    parts.push(t('insights.quotas.uncounted', { amount: fmtCost(amount, cur) }));
  }
  return parts.join(' · ');
}

// ── Per-project usage section ────────────────────────────────────────────
const projectRows = computed<ProjectUsageRow[]>(
  () => summariseProjectBuckets(usage.byProject.value?.buckets ?? []),
);

const totalCalls = computed<number>(() => projectRows.value.reduce((acc, r) => acc + r.calls, 0));
const totalUnpriced = computed<number>(() => projectRows.value.reduce((acc, r) => acc + r.unpricedCalls, 0));
const coveragePct = computed<number>(() => {
  const total = totalCalls.value;
  if (total <= 0) return 100;
  return Math.round(((total - totalUnpriced.value) / total) * 100);
});

function costByCurrency(rows: ProjectUsageRow[]): Map<string, number> {
  const map = new Map<string, number>();
  for (const r of rows) {
    if (!r.currency) continue;
    map.set(r.currency, (map.get(r.currency) ?? 0) + r.costTotal);
  }
  return map;
}

const projectTotalsByCurrency = computed<Map<string, number>>(() => costByCurrency(projectRows.value));
</script>

<template>
  <div class="quotas-tab flex flex-col gap-6 p-4 max-w-5xl">
    <!-- ─── Toolbar ─── -->
    <div class="flex flex-wrap items-center gap-3 text-sm">
      <VButton variant="secondary" size="sm" icon="refresh" :disabled="loading" @click="refresh">
        {{ $t('insights.quotas.refresh') }}
      </VButton>
      <div class="text-xs opacity-60">{{ $t('insights.quotas.hint') }}</div>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <!-- ─── Documents / Storage ─── -->
    <section class="flex flex-col gap-2">
      <h3 class="quotas-heading">{{ $t('insights.quotas.documentsHeading') }}</h3>

      <VCard :title="$t('insights.quotas.storageTitle')">
        <p class="quotas-storage__hint">
          {{
            cleanupProjectId
              ? $t('insights.quotas.scopeProject', { project: cleanupProjectId })
              : $t('insights.quotas.scopeTenantHint')
          }}
        </p>
        <div class="quotas-storage__controls">
          <div class="quotas-storage__actions">
            <VButton
              variant="secondary"
              size="sm"
              icon="refresh"
              :disabled="cleanupLoading || cleanupBusy"
              @click="refreshCleanup"
            >
              {{ $t('insights.quotas.refreshBreakdown') }}
            </VButton>
            <VButton
              variant="secondary"
              size="sm"
              icon="delete"
              :disabled="cleanupBusy || (cleanup.breakdown.value?.trashBytes ?? 0) === 0"
              :loading="cleanupBusy"
              @click="onEmptyTrash"
            >
              {{ $t('insights.quotas.emptyTrash') }}
            </VButton>
            <VButton
              variant="secondary"
              size="sm"
              icon="crop"
              :disabled="cleanupBusy || (cleanup.breakdown.value?.archiveCount ?? 0) === 0"
              @click="onOpenArchiveDialog"
            >
              {{ $t('insights.quotas.thinArchives') }}
            </VButton>
          </div>
        </div>

        <VAlert v-if="cleanupError" variant="error" class="mt-3">
          <span>{{ cleanupError }}</span>
        </VAlert>

        <VAlert v-if="lastCleanup" variant="success" class="mt-3">
          <span>
            {{
              $t('insights.quotas.cleanupDone', {
                action: lastCleanup.action === 'trash'
                  ? $t('insights.quotas.cleanupActionTrash')
                  : $t('insights.quotas.cleanupActionArchives'),
                bytes: formatBytesShort(lastCleanup.freedBytes),
              })
            }}
          </span>
        </VAlert>

        <VGroup class="mt-3">
          <VGroupRow
            :label="$t('insights.quotas.storageLive')"
            :value="cleanup.breakdown.value ? formatBytesShort(cleanup.breakdown.value.liveBytes) : '—'"
          />
          <VGroupRow
            :label="$t('insights.quotas.storageTrash')"
            :description="$t('insights.quotas.storageTrashHint', { count: cleanup.breakdown.value?.trashCount ?? 0 })"
            :value="cleanup.breakdown.value ? formatBytesShort(cleanup.breakdown.value.trashBytes) : '—'"
          />
          <VGroupRow
            :label="$t('insights.quotas.storageArchives')"
            :description="$t('insights.quotas.storageArchivesHint', { count: cleanup.breakdown.value?.archiveCount ?? 0 })"
            :value="cleanup.breakdown.value ? formatBytesShort(cleanup.breakdown.value.archiveBytes) : '—'"
          />
        </VGroup>

        <p
          v-if="storagePercent != null && storagePercent >= 0"
          class="quotas-meter"
          :data-severity="storageSeverity"
        >
          <span class="quotas-meter__label">{{ storageRowLabel() }}</span>
          <span class="quotas-meter__track" aria-hidden="true">
            <span class="quotas-meter__fill" :style="{ width: `${Math.min(100, storagePercent)}%` }" />
          </span>
        </p>

        <p
          v-if="cleanup.breakdown.value && cleanup.breakdown.value.reclaimableBytes > 0"
          class="quotas-storage__hint"
        >
          {{
            $t('insights.quotas.reclaimableHint', {
              bytes: formatBytesShort(cleanup.breakdown.value.reclaimableBytes),
            })
          }}
        </p>
      </VCard>

      <CleanupDialog
        :open="cleanupDialogOpen"
        :project-id="cleanupProjectId"
        :breakdown="cleanup.breakdown.value"
        @close="cleanupDialogOpen = false"
        @done="onArchiveDone"
      />
    </section>

    <!-- ─── LLM quota + budget ─── -->
    <section class="flex flex-col gap-2">
      <h3 class="quotas-heading">{{ $t('insights.quotas.llmHeading') }}</h3>

      <VCard :title="$t('insights.quotas.powerTitle')">
        <VGroup>
          <VGroupRow
            :label="$t('insights.quotas.powerRow')"
            :description="llmPowerOn === false ? $t('insights.quotas.powerOffBody') : $t('insights.quotas.powerOnBody')"
          >
            <VBadge
              v-if="llmPowerOn === false"
              variant="error"
              size="sm"
            >{{ $t('insights.quotas.powerOff') }}</VBadge>
            <VBadge
              v-else-if="llmPowerOn === true"
              variant="success"
              size="sm"
            >{{ $t('insights.quotas.powerOn') }}</VBadge>
            <span v-else class="text-xs opacity-60">{{ $t('insights.quotas.unknown') }}</span>
            <VIcon name="power" class="opacity-60" />
          </VGroupRow>
        </VGroup>
      </VCard>

      <VCard v-if="tenantBudget || projectBudgets.length > 0" :title="$t('insights.quotas.budgetTitle')">
        <p class="text-xs opacity-70 mb-2">
          {{ $t('insights.quotas.budgetHint') }}
        </p>

        <VGroup
          v-if="tenantBudget"
          :label="$t('insights.quotas.budgetTenant')"
          :note="tenantBudget.totalIncomplete ? $t('insights.quotas.budgetTotalIncomplete') : undefined"
        >
          <VGroupRow
            v-if="tenantBudget.windows.length === 0"
            :label="$t('insights.quotas.budgetNone')"
            :description="$t('insights.quotas.budgetNoneHint')"
            disabled
          />
          <VGroupRow
            v-for="w in tenantBudget.windows"
            :key="`tenant-${w.window}-${w.from}`"
            :label="windowLabel(w)"
            :description="windowGaps(w) || undefined"
            :value="windowValue(w, budgetCurrency(tenantBudget!))"
          >
            <VBadge v-if="w.exhausted" variant="error" size="sm">
              {{ $t('insights.quotas.budgetExhausted') }}
            </VBadge>
            <VBadge v-else-if="w.warning" variant="warning" size="sm">
              {{ $t('insights.quotas.budgetWarning') }}
            </VBadge>
          </VGroupRow>
        </VGroup>

        <div
          v-for="b in projectBudgets"
          :key="`proj-${b.scopeId}`"
          class="mt-3"
        >
          <VGroup
            :label="t('insights.quotas.budgetProject', { project: b.scopeId })"
            :note="b.totalIncomplete ? $t('insights.quotas.budgetTotalIncomplete') : undefined"
          >
            <VGroupRow
              v-if="b.windows.length === 0"
              :label="$t('insights.quotas.budgetNone')"
              :description="$t('insights.quotas.budgetNoneHint')"
              disabled
            />
            <VGroupRow
              v-for="w in b.windows"
              :key="`${b.scopeId}-${w.window}-${w.from}`"
              :label="windowLabel(w)"
              :description="windowGaps(w) || undefined"
              :value="windowValue(w, budgetCurrency(b))"
            >
              <VBadge v-if="w.exhausted" variant="error" size="sm">
                {{ $t('insights.quotas.budgetExhausted') }}
              </VBadge>
              <VBadge v-else-if="w.warning" variant="warning" size="sm">
                {{ $t('insights.quotas.budgetWarning') }}
              </VBadge>
            </VGroupRow>
          </VGroup>
        </div>
      </VCard>

      <VAlert v-if="adminMissing" variant="info">
        {{ $t('insights.quotas.adminOnly') }}
      </VAlert>
    </section>

    <!-- ─── Per-project LLM usage ─── -->
    <section class="flex flex-col gap-2">
      <h3 class="quotas-heading">{{ $t('insights.quotas.projectHeading') }}</h3>

      <VCard :title="$t('insights.quotas.projectCardTitle')">
        <template v-if="projectRows.length === 0 && !loading">
          <VEmptyState
            :headline="$t('insights.quotas.projectEmptyHeadline')"
            :body="$t('insights.quotas.projectEmptyBody')"
          />
        </template>

        <template v-else>
          <div class="quotas-totals">
            <div>
              <span class="quotas-totals__label">{{ $t('insights.quotas.totalCalls') }}</span>
              <strong>{{ totalCalls }}</strong>
            </div>
            <div v-for="[cur, sum] in projectTotalsByCurrency" :key="cur">
              <span class="quotas-totals__label">{{ $t('insights.quotas.totalCost') }}</span>
              <strong>{{ fmtCost(sum, cur) }}</strong>
            </div>
            <div v-if="totalUnpriced > 0">
              <span class="quotas-totals__label">{{ $t('insights.quotas.coverageLabel') }}</span>
              <strong>{{ $t('insights.quotas.coveragePartial', { pct: coveragePct, unpriced: totalUnpriced }) }}</strong>
            </div>
          </div>

          <table class="quotas-table">
            <thead>
              <tr>
                <th>{{ $t('insights.quotas.colProject') }}</th>
                <th class="num">{{ $t('insights.quotas.colCalls') }}</th>
                <th class="num">{{ $t('insights.quotas.colTokensIn') }}</th>
                <th class="num">{{ $t('insights.quotas.colTokensOut') }}</th>
                <th class="num">{{ $t('insights.quotas.colCost') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in projectRows" :key="r.key">
                <td class="font-mono">{{ r.key || '—' }}</td>
                <td class="num">
                  {{ r.calls }}
                  <span v-if="r.callsFailed > 0" class="text-xs opacity-60">
                    · {{ $t('insights.quotas.failedSuffix', { n: r.callsFailed }) }}
                  </span>
                </td>
                <td class="num">{{ fmtTokens(r.tokensIn) }}</td>
                <td class="num">{{ fmtTokens(r.tokensOut) }}</td>
                <td class="num">{{ r.currency ? fmtCost(r.costTotal, r.currency) : 'n/a' }}</td>
              </tr>
            </tbody>
          </table>

          <p class="text-xs opacity-60 mt-2">
            {{ $t('insights.quotas.windowLabel', { days: rangeDays }) }}
          </p>
        </template>
      </VCard>
    </section>
  </div>
</template>

<style scoped>
.quotas-storage__controls {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
  justify-content: space-between;
}
.quotas-storage__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.quotas-storage__hint {
  margin: 8px 0 0;
  font-size: 0.75rem;
  color: color-mix(in oklab, var(--color-base-content) 60%, transparent);
}
.quotas-heading {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--v-label-secondary);
  margin: 0;
  padding: 0 4px;
}
.quotas-meter {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 8px 0 0;
}
.quotas-meter__label {
  font-variant-numeric: tabular-nums;
  font-size: 0.875rem;
  min-width: 4ch;
  text-align: right;
}
.quotas-meter__track {
  flex: 1;
  height: 6px;
  background: color-mix(in oklab, var(--color-base-content) 12%, transparent);
  border-radius: 9999px;
  overflow: hidden;
}
.quotas-meter__fill {
  display: block;
  height: 100%;
  background: var(--v-success);
  transition: width 200ms ease;
}
.quotas-meter[data-severity='warning'] .quotas-meter__fill {
  background: var(--v-warning);
}
.quotas-meter[data-severity='danger'] .quotas-meter__fill {
  background: var(--v-danger);
}
.quotas-totals {
  display: flex;
  gap: 1.5rem;
  flex-wrap: wrap;
  margin-bottom: 0.75rem;
}
.quotas-totals > div {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}
.quotas-totals strong {
  font-size: 1.125rem;
  font-variant-numeric: tabular-nums;
}
.quotas-totals__label {
  font-size: 0.75rem;
  color: color-mix(in oklab, var(--color-base-content) 60%, transparent);
}
.quotas-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}
.quotas-table th,
.quotas-table td {
  text-align: left;
  padding: 8px 12px;
  border-top: 1px solid color-mix(in oklab, var(--color-base-content) 10%, transparent);
}
.quotas-table thead th {
  font-weight: 600;
  font-size: 0.75rem;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  color: var(--v-label-secondary);
  border-top: 0;
}
.quotas-table .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
</style>
