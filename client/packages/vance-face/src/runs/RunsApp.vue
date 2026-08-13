<script setup lang="ts">
/**
 * The run view: instances, not definitions.
 *
 * <p>Every runtime that produces runs contributes rows through the same
 * server-side facade — Magrathea workflows, plan-shaped ThinkProcesses
 * (Vogon, Marvin), Damogran compose runs. The page never branches on
 * which one a row came from; the source is a filter and a label, and the
 * only source-specific rendering is the extra block on the detail.
 *
 * <p>Reachable by deep link only (from the Cortex flow view, from
 * Insights). It is deliberately absent from the landing page: a list of
 * instances is where you go when you are looking for one, not somewhere
 * you browse.
 *
 * <p>Design: {@code planning/runs-view.md}.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, VAlert, VButton, VEmptyState, VSelect } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useRuns } from './useRuns';
import RunStatusBadge from './RunStatusBadge.vue';
import RunDetailPanel from './RunDetailPanel.vue';

const { t } = useI18n();
const { projects, reload: reloadProjects } = useTenantProjects();
const { runs, detail, loading, detailLoading, error, acting,
  loadRuns, loadDetail, perform, clearDetail } = useRuns();

const params = new URLSearchParams(window.location.search);
const projectId = ref<string>(params.get('project') ?? '');
const selectedRunId = ref<string>(params.get('run') ?? '');
const sourceFilter = ref<string>('');

const focusZone = ref<'sidebar' | 'main' | 'right' | 'footer'>('main');

const projectOptions = computed(() =>
  projects.value.map((p) => ({ value: p.name, label: p.title || p.name })));

/** Sources present in the current list — a filter built from data. */
const sourceOptions = computed(() => {
  const seen = [...new Set(runs.value.map((r) => r.source))];
  return [{ value: '', label: t('runs.filter.allSources') },
    ...seen.map((s) => ({ value: s, label: s }))];
});

const visibleRuns = computed(() => sourceFilter.value
  ? runs.value.filter((r) => r.source === sourceFilter.value)
  : runs.value);

/** Keep the URL addressable — the whole point of the deep link. */
function syncUrl(): void {
  const next = new URLSearchParams();
  if (projectId.value) next.set('project', projectId.value);
  if (selectedRunId.value) next.set('run', selectedRunId.value);
  window.history.replaceState({}, '', `${window.location.pathname}?${next}`);
}

async function selectRun(runId: string): Promise<void> {
  selectedRunId.value = runId;
  syncUrl();
  if (projectId.value) await loadDetail(projectId.value, runId);
}

/** Act, then reload the list: a stopped run changes its row too. */
async function runAction(action: string): Promise<void> {
  if (!projectId.value || !selectedRunId.value) return;
  await perform(projectId.value, selectedRunId.value, action);
  await loadRuns(projectId.value);
}

async function refresh(): Promise<void> {
  if (!projectId.value) return;
  await loadRuns(projectId.value);
  if (selectedRunId.value) await loadDetail(projectId.value, selectedRunId.value);
}

watch(projectId, async () => {
  selectedRunId.value = '';
  clearDetail();
  syncUrl();
  await refresh();
});

onMounted(async () => {
  await reloadProjects();
  if (!projectId.value && projects.value.length > 0) {
    projectId.value = projects.value[0].name;
    return; // the watcher loads
  }
  await refresh();
});
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    :title="t('runs.pageTitle')"
    :full-height="true"
    :show-sidebar="true"
    focus-model="auto"
  >
    <template #topbar-extra>
      <VSelect
        v-model="projectId"
        size="sm"
        :options="projectOptions"
        :aria-label="t('runs.filter.project')"
      />
      <VButton size="sm" variant="ghost" :disabled="loading" @click="refresh">
        ⟳ {{ t('runs.refresh') }}
      </VButton>
    </template>

    <template #sidebar>
      <div class="list">
        <div class="list-head">
          <VSelect v-model="sourceFilter" size="sm" :options="sourceOptions" />
          <span class="count">{{ t('runs.count', { n: visibleRuns.length }) }}</span>
        </div>

        <p v-if="loading" class="hint">{{ t('runs.loading') }}</p>
        <VEmptyState
          v-else-if="visibleRuns.length === 0"
          :headline="t('runs.empty.headline')"
          :body="t('runs.empty.body')"
        />
        <ul v-else class="rows">
          <li v-for="run in visibleRuns" :key="run.runId">
            <button
              type="button"
              :class="['row', { 'row--active': run.runId === selectedRunId }]"
              @click="selectRun(run.runId)"
            >
              <span class="row-top">
                <span class="row-name">{{ run.name }}</span>
                <RunStatusBadge :status="run.status" />
              </span>
              <span class="row-sub">
                <span class="row-source">{{ run.source }}</span>
                <span v-if="run.step">· {{ run.step }}</span>
                <span v-if="run.startedAt" class="row-time">{{ run.startedAt }}</span>
              </span>
            </button>
          </li>
        </ul>
      </div>
    </template>

    <div class="main">
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      <p v-if="detailLoading" class="hint">{{ t('runs.loadingDetail') }}</p>
      <RunDetailPanel
        v-else-if="detail"
        :detail="detail"
        :project-id="projectId"
        :acting="acting"
        @open-run="selectRun"
        @action="runAction"
      />
      <VEmptyState
        v-else
        :headline="t('runs.select.headline')"
        :body="t('runs.select.body')"
      />
    </div>
  </EditorShell>
</template>

<style scoped>
.list { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.list-head {
  display: flex; align-items: center; gap: 0.5rem;
  padding: 0.5rem; border-bottom: 1px solid var(--color-base-300);
}
.count { font-size: 0.72rem; opacity: 0.6; margin-left: auto; }
.rows { list-style: none; margin: 0; padding: 0; overflow-y: auto; }
.row {
  display: flex; flex-direction: column; gap: 0.15rem; width: 100%;
  padding: 0.5rem 0.6rem; text-align: left; background: transparent;
  border: 0; border-bottom: 1px solid var(--color-base-300); cursor: pointer;
}
.row:hover { background: color-mix(in oklab, var(--color-base-content) 5%, transparent); }
.row--active { background: color-mix(in oklab, var(--color-primary) 12%, transparent); }
.row-top { display: flex; align-items: center; gap: 0.4rem; }
.row-name {
  font-size: 0.85rem; font-weight: 600; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.row-sub { display: flex; gap: 0.3rem; font-size: 0.7rem; opacity: 0.6; }
.row-source { font-family: ui-monospace, monospace; }
.row-time { margin-left: auto; }
.main { padding: 0.75rem; overflow-y: auto; height: 100%; }
.hint { font-size: 0.85rem; opacity: 0.6; }
</style>
