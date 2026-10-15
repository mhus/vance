<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { VAlert, VButton, VCheckbox, VIconButton, VInput, VModal, VSelect } from '@vance/components';
import FinanceTreeNode from './FinanceTreeNode.vue';
import * as ops from './treeOps';
import { calc, generateReport, getSnapshot, getTree, listProcessors, putTree } from './api';
import type { FinanceNodeDto } from './generated/finance/FinanceNodeDto';
import type { FinanceTreeDto } from './generated/finance/FinanceTreeDto';
import type { FinanceValueDto } from './generated/finance/FinanceValueDto';
import type {
  Granularity,
  NodeAction,
  NodeSnapshot,
  ProcessorInfo,
  ReportResult,
} from './types';
import { useT } from './i18n';

const props = defineProps<{
  document: { id?: string; path: string; projectId: string };
}>();

const t = useT();

const tree = ref<FinanceTreeDto | null>(null);
const selectedName = ref<string | null>(null);
const computedMap = ref<Record<string, NodeSnapshot> | null>(null);
const displayUnit = ref<'year' | 'month' | 'week' | 'day'>('year');
const processors = ref<ProcessorInfo[]>([]);
const error = ref<string | null>(null);
const loading = ref(false);
const saveState = ref<'saved' | 'dirty' | 'saving' | 'error'>('saved');

let dirtyEnabled = false;
let saveTimer: ReturnType<typeof setTimeout> | null = null;

const unitKey = computed<'perYear' | 'perMonth' | 'perWeek' | 'perDay'>(() => {
  switch (displayUnit.value) {
    case 'month': return 'perMonth';
    case 'week': return 'perWeek';
    case 'day': return 'perDay';
    default: return 'perYear';
  }
});

const selected = computed<FinanceNodeDto | null>(() => {
  if (!tree.value || !selectedName.value) return null;
  return ops.locate(tree.value, selectedName.value)?.node ?? null;
});

// ── Load / save ───────────────────────────────────────────────

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  dirtyEnabled = false;
  try {
    tree.value = await getTree(props.document.projectId, props.document.path);
    processors.value = await listProcessors(props.document.projectId);
    await nextTick();
    dirtyEnabled = true;
    await refreshSnapshot();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

/** Live, ephemeral recompute (read-only, no persist) → per-node figures. */
async function refreshSnapshot(): Promise<void> {
  try {
    const c = await getSnapshot(props.document.projectId, props.document.path);
    const map: Record<string, NodeSnapshot> = {};
    for (const n of c.nodes) map[n.name] = n;
    computedMap.value = map;
  } catch {
    /* keep the previous figures if a recompute fails */
  }
}

function markDirty(): void {
  saveState.value = 'dirty';
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(save, 800);
}

async function save(): Promise<void> {
  if (!tree.value) return;
  saveState.value = 'saving';
  try {
    await putTree(props.document.projectId, props.document.path, tree.value);
    saveState.value = 'saved';
    await refreshSnapshot(); // live figures reflect the just-saved edits
  } catch (e) {
    saveState.value = 'error';
    error.value = e instanceof Error ? e.message : String(e);
  }
}

watch(tree, () => { if (dirtyEnabled) markDirty(); }, { deep: true });
watch(() => [props.document.projectId, props.document.path], () => { void load(); });
onMounted(load);
onUnmounted(() => { if (saveTimer) clearTimeout(saveTimer); });

// ── Tree actions ──────────────────────────────────────────────

function handleAction(action: NodeAction, name: string): void {
  if (!tree.value) return;
  const t = tree.value;
  switch (action) {
    case 'select': selectedName.value = name; break;
    case 'add-child': {
      const child = ops.newNode(ops.nextName(t), 'New node');
      ops.addChild(t, name, child);
      selectedName.value = child.name;
      break;
    }
    case 'remove':
      ops.removeNode(t, name);
      if (selectedName.value === name) selectedName.value = null;
      break;
    case 'move-up': ops.move(t, name, -1); break;
    case 'move-down': ops.move(t, name, 1); break;
    case 'indent': ops.indent(t, name); break;
    case 'outdent': ops.outdent(t, name); break;
  }
}

function addRoot(): void {
  if (!tree.value || tree.value.root) return;
  const root = ops.newNode(ops.nextName(tree.value), 'Root');
  ops.addChild(tree.value, null, root);
  selectedName.value = root.name;
}

async function reload(): Promise<void> {
  try {
    const c = await calc(props.document.projectId, props.document.path);
    const map: Record<string, NodeSnapshot> = {};
    for (const n of c.nodes) map[n.name] = n;
    computedMap.value = map;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

// ── Detail-panel editing helpers ──────────────────────────────

function num(e: Event): number {
  const v = parseFloat((e.target as HTMLInputElement).value);
  return Number.isFinite(v) ? v : 0;
}

function strOrNull(e: Event): string | undefined {
  const v = (e.target as HTMLInputElement).value.trim();
  return v === '' ? undefined : v;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function addValue(node: FinanceNodeDto): void {
  node.values.push({ value: 0, mode: 'recurring', period: { count: 1, unit: 'month' } });
}

function removeValue(node: FinanceNodeDto, index: number): void {
  node.values.splice(index, 1);
}

function onModeChange(v: FinanceValueDto, mode: string): void {
  v.mode = mode;
  if (mode === 'recurring' && !v.period) v.period = { count: 1, unit: 'month' };
  if (mode === 'one_time' && !v.validFrom) v.validFrom = today();
}

function toggleInterest(v: FinanceValueDto): void {
  v.interest = v.interest
    ? undefined
    : { rate: 0, period: { count: 1, unit: 'year' }, basis: 'vom_hundert', compound: false };
}

function toggleSign(node: FinanceNodeDto): void {
  node.sign = node.sign < 0 ? 1 : -1;
}

// ── Report panel ──────────────────────────────────────────────

const reportOpen = ref(false);
const reportRunning = ref(false);
const reportResult = ref<ReportResult | null>(null);
const reportForm = reactive({
  processor: '',
  from: `${new Date().getFullYear()}-01-01`,
  to: `${new Date().getFullYear() + 1}-01-01`,
  granularity: 'month' as Granularity,
  chartType: 'line',
  focus: '',
  persist: false,
  outputPath: '',
});

const processorOptions = computed(() =>
  processors.value.map((p) => ({ value: p.type, label: `${p.title} (${p.outputKind})` })),
);

const granularityOptions = computed<{ value: Granularity; label: string }[]>(() =>
  (['day', 'week', 'month', 'year'] as const).map((g) => ({ value: g, label: t(`finance.period.${g}`) })),
);

const chartTypeOptions = computed(() =>
  (['line', 'bar', 'area', 'scatter'] as const).map((c) => ({ value: c, label: t(`finance.chartType.${c}`) })),
);

function openReport(): void {
  if (!reportForm.processor && processors.value.length > 0) {
    reportForm.processor = processors.value[0].type;
  }
  reportResult.value = null;
  reportOpen.value = true;
}

async function runReport(): Promise<void> {
  if (!reportForm.processor || reportRunning.value) return;
  const params: Record<string, unknown> = {
    from: reportForm.from,
    to: reportForm.to,
    granularity: reportForm.granularity,
    chartType: reportForm.chartType,
  };
  if (reportForm.focus.trim()) params.focus = reportForm.focus.trim();
  reportRunning.value = true;
  reportResult.value = null;
  try {
    reportResult.value = await generateReport(
      props.document.projectId,
      props.document.path,
      reportForm.processor,
      params,
      reportForm.persist,
      reportForm.persist ? reportForm.outputPath : undefined,
    );
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    reportRunning.value = false;
  }
}
</script>

<template>
  <div class="flex flex-col h-full text-sm">
    <!-- Toolbar -->
    <div class="flex items-center gap-2 border-b border-(--v-separator) px-3 py-2">
      <span class="font-semibold">{{ tree?.title || t('finance.fallbackTitle') }}</span>
      <VButton icon="refresh" variant="plain" @click="reload">{{ t('finance.reload') }}</VButton>
      <label class="flex items-center gap-1">
        <span class="opacity-70">{{ t('finance.unit') }}</span>
        <select v-model="displayUnit" class="border border-(--v-border) rounded px-1 py-0.5 bg-transparent">
          <option value="year">{{ t('finance.perUnit.year') }}</option>
          <option value="month">{{ t('finance.perUnit.month') }}</option>
          <option value="week">{{ t('finance.perUnit.week') }}</option>
          <option value="day">{{ t('finance.perUnit.day') }}</option>
        </select>
      </label>
      <VButton icon="play" variant="secondary" @click="openReport">{{ t('finance.report') }}</VButton>
      <span class="ml-auto text-xs opacity-60">{{ t(`finance.save.${saveState}`) }}</span>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <div v-if="loading" class="p-4 opacity-60">{{ t('finance.loading') }}</div>

    <div v-else class="flex flex-1 min-h-0">
      <!-- Tree (left) -->
      <div class="w-1/2 overflow-auto border-r border-(--v-separator) p-2">
        <FinanceTreeNode
          v-if="tree?.root"
          :node="tree.root"
          :depth="0"
          :selected-name="selectedName"
          :computed-map="computedMap"
          :unit-key="unitKey"
          :on-action="handleAction"
        />
        <VButton v-else variant="primary" icon="add" @click="addRoot">{{ t('finance.createRoot') }}</VButton>
      </div>

      <!-- Detail (right) -->
      <div class="w-1/2 overflow-auto p-3">
        <div v-if="!selected" class="opacity-60">{{ t('finance.pickNode') }}</div>
        <div v-else class="flex flex-col gap-4">
          <!-- Fixed fields -->
          <section class="flex flex-col gap-2">
            <div class="font-semibold opacity-70">{{ t('finance.fields.heading', { name: selected.name }) }}</div>
            <label class="flex flex-col gap-0.5">
              <span class="opacity-70">{{ t('finance.fields.title') }}</span>
              <input :value="selected.title ?? ''" class="fx-in"
                     @input="selected.title = strOrNull($event)" />
            </label>
            <div class="flex gap-2">
              <label class="flex flex-col gap-0.5 w-20">
                <span class="opacity-70">{{ t('finance.fields.icon') }}</span>
                <input :value="selected.icon ?? ''" class="fx-in"
                       @input="selected.icon = strOrNull($event)" />
              </label>
              <label class="flex flex-col gap-0.5 w-28">
                <span class="opacity-70">{{ t('finance.fields.color') }}</span>
                <input :value="selected.color ?? ''" class="fx-in" placeholder="#4f8"
                       @input="selected.color = strOrNull($event)" />
              </label>
              <label class="flex items-end gap-1 pb-1">
                <input type="checkbox" :checked="selected.sign < 0" @change="toggleSign(selected)" />
                <span class="opacity-70">{{ t('finance.fields.negative') }}</span>
              </label>
            </div>
            <label class="flex flex-col gap-0.5">
              <span class="opacity-70">{{ t('finance.fields.description') }}</span>
              <textarea :value="selected.description ?? ''" rows="2" class="fx-in"
                        @input="selected.description = strOrNull($event)" />
            </label>
            <label class="flex flex-col gap-0.5">
              <span class="opacity-70">{{ t('finance.fields.notesRef') }}</span>
              <input :value="selected.notesRef ?? ''" class="fx-in"
                     @input="selected.notesRef = strOrNull($event)" />
            </label>
          </section>

          <!-- Value records -->
          <section class="flex flex-col gap-2">
            <div class="flex items-center gap-2">
              <span class="font-semibold opacity-70">{{ t('finance.values.heading') }}</span>
              <VButton variant="plain" icon="add" @click="addValue(selected)">{{ t('finance.values.add') }}</VButton>
            </div>
            <div v-for="(v, i) in selected.values" :key="i"
                 class="border border-(--v-separator) rounded p-2 flex flex-col gap-2">
              <div class="flex gap-2 items-end">
                <label class="flex flex-col gap-0.5 w-28">
                  <span class="opacity-70">{{ t('finance.values.amount') }}</span>
                  <input type="number" :value="v.value" class="fx-in"
                         @input="v.value = num($event)" />
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="opacity-70">{{ t('finance.values.mode') }}</span>
                  <select :value="v.mode" class="fx-in"
                          @change="onModeChange(v, ($event.target as HTMLSelectElement).value)">
                    <option value="recurring">{{ t('finance.values.modeRecurring') }}</option>
                    <option value="one_time">{{ t('finance.values.modeOneTime') }}</option>
                  </select>
                </label>
                <VIconButton icon="delete" danger :label="t('finance.values.remove')" @click="removeValue(selected, i)" />
              </div>

              <div v-if="v.mode === 'recurring' && v.period" class="flex gap-2 items-end">
                <label class="flex flex-col gap-0.5 w-20">
                  <span class="opacity-70">{{ t('finance.values.per') }}</span>
                  <input type="number" :value="v.period.count" class="fx-in"
                         @input="v.period && (v.period.count = num($event))" />
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="opacity-70">{{ t('finance.values.unit') }}</span>
                  <select :value="v.period.unit" class="fx-in"
                          @change="v.period && (v.period.unit = ($event.target as HTMLSelectElement).value)">
                    <option value="day">{{ t('finance.period.day') }}</option>
                    <option value="week">{{ t('finance.period.week') }}</option>
                    <option value="month">{{ t('finance.period.month') }}</option>
                    <option value="year">{{ t('finance.period.year') }}</option>
                  </select>
                </label>
              </div>

              <div class="flex gap-2 items-end">
                <label class="flex flex-col gap-0.5">
                  <span class="opacity-70">{{ v.mode === 'one_time'
                    ? t('finance.values.date')
                    : t('finance.values.validFrom') }}</span>
                  <input type="date" :value="v.validFrom ?? ''" class="fx-in"
                         @input="v.validFrom = strOrNull($event)" />
                </label>
                <label v-if="v.mode === 'recurring'" class="flex flex-col gap-0.5">
                  <span class="opacity-70">{{ t('finance.values.validTo') }}</span>
                  <input type="date" :value="v.validTo ?? ''" class="fx-in"
                         @input="v.validTo = strOrNull($event)" />
                </label>
              </div>

              <div class="flex items-center gap-2">
                <label class="flex items-center gap-1">
                  <input type="checkbox" :checked="!!v.interest" @change="toggleInterest(v)" />
                  <span class="opacity-70">{{ t('finance.values.interest') }}</span>
                </label>
                <template v-if="v.interest">
                  <label class="flex items-center gap-1">
                    <span class="opacity-70">{{ t('finance.values.rate') }}</span>
                    <input type="number" :value="v.interest.rate" class="fx-in w-16"
                           @input="v.interest && (v.interest.rate = num($event))" />
                  </label>
                  <select v-if="v.interest.period" :value="v.interest.period.unit" class="fx-in"
                          @change="v.interest?.period && (v.interest.period.unit = ($event.target as HTMLSelectElement).value)">
                    <option value="day">{{ t('finance.perUnit.day') }}</option>
                    <option value="week">{{ t('finance.perUnit.week') }}</option>
                    <option value="month">{{ t('finance.perUnit.month') }}</option>
                    <option value="year">{{ t('finance.perUnit.year') }}</option>
                  </select>
                  <label class="flex items-center gap-1">
                    <input type="checkbox" :checked="v.interest.compound"
                           @change="v.interest && (v.interest.compound = ($event.target as HTMLInputElement).checked)" />
                    <span class="opacity-70">{{ t('finance.values.compound') }}</span>
                  </label>
                </template>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>

    <!-- Report modal -->
    <VModal v-model="reportOpen" :title="t('finance.reportModal.title')">
      <form id="finance-report-form" class="flex flex-col gap-4" @submit.prevent="runReport">
        <VSelect
          :model-value="reportForm.processor"
          :options="processorOptions"
          :label="t('finance.reportModal.processor')"
          @update:model-value="(v) => { reportForm.processor = v ?? ''; }"
        />
        <div class="flex gap-2">
          <div class="min-w-0 flex-1">
            <VInput v-model="reportForm.from" type="date" :label="t('finance.reportModal.from')" />
          </div>
          <div class="min-w-0 flex-1">
            <VInput v-model="reportForm.to" type="date" :label="t('finance.reportModal.to')" />
          </div>
          <div class="min-w-0 flex-1">
            <VSelect
              :model-value="reportForm.granularity"
              :options="granularityOptions"
              :label="t('finance.reportModal.granularity')"
              @update:model-value="(v) => { if (v) reportForm.granularity = v; }"
            />
          </div>
        </div>
        <VSelect
          :model-value="reportForm.chartType"
          :options="chartTypeOptions"
          :label="t('finance.reportModal.chartType')"
          @update:model-value="(v) => { if (v) reportForm.chartType = v; }"
        />
        <VInput v-model="reportForm.focus" :label="t('finance.reportModal.focus')" />
        <VCheckbox v-model="reportForm.persist" :label="t('finance.reportModal.persist')" />
        <VInput
          v-if="reportForm.persist"
          v-model="reportForm.outputPath"
          :label="t('finance.reportModal.outputPath')"
          :placeholder="t('finance.reportModal.outputPathPlaceholder')"
        />

        <div v-if="reportRunning" class="text-sm text-(--v-label-secondary)">{{ t('finance.reportModal.running') }}</div>
        <div v-else-if="reportResult" class="text-sm">
          <div v-if="reportResult.path" class="text-(--v-success)">
            {{ t('finance.reportModal.savedTo', { path: reportResult.path }) }}
          </div>
          <pre v-else class="max-h-64 overflow-auto text-xs bg-(--v-fill) p-2 rounded">{{ reportResult.body }}</pre>
        </div>
      </form>

      <template #actions>
        <VButton variant="secondary" @click="reportOpen = false">{{ t('finance.reportModal.close') }}</VButton>
        <VButton
          type="submit"
          form="finance-report-form"
          variant="primary"
          :loading="reportRunning"
          :disabled="reportRunning || !reportForm.processor"
        >
          {{ reportRunning ? t('finance.reportModal.generating') : t('finance.reportModal.generate') }}
        </VButton>
      </template>
    </VModal>
  </div>
</template>

<style scoped>
.fx-in {
  border: 1px solid rgba(128, 128, 128, 0.35);
  border-radius: 0.25rem;
  padding: 0.15rem 0.4rem;
  background: transparent;
}
</style>
