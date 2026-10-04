<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ThinkProcessStatus, type TrillianInsightsDto } from '@vance/generated';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useTrillianInsights } from '@/composables/useTrillianInsights';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
const state = useTrillianInsights();
const actionError = ref<string | null>(null);
const busyId = ref<string | null>(null);

onMounted(() => {
  void state.load();
});

function refresh(): void {
  void state.load();
}

/** The Trillian's face: the account title humans renamed, else the account, else the session. */
function displayName(item: TrillianInsightsDto): string {
  return item.worker?.accountTitle || item.worker?.accountId || item.control.sessionId;
}

function canPause(item: TrillianInsightsDto): boolean {
  const w = item.worker;
  return !!w && w.status !== ThinkProcessStatus.PAUSED && w.status !== ThinkProcessStatus.CLOSED;
}

function canResume(item: TrillianInsightsDto): boolean {
  const w = item.worker;
  return !!w && w.status !== ThinkProcessStatus.RUNNING && w.status !== ThinkProcessStatus.CLOSED;
}

async function pause(item: TrillianInsightsDto): Promise<void> {
  await act(item, state.pause);
}

async function resume(item: TrillianInsightsDto): Promise<void> {
  await act(item, state.resume);
}

async function act(
  item: TrillianInsightsDto,
  fn: (controlProcessId: string) => Promise<void>,
): Promise<void> {
  busyId.value = item.control.processId;
  actionError.value = null;
  try {
    await fn(item.control.processId);
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : String(e);
  } finally {
    busyId.value = null;
  }
}

/** Relative age, same vocabulary as the session list. */
function fmtAge(value: Date | string | null | undefined): string {
  if (value == null) return '';
  const ts = value instanceof Date ? value.getTime() : Date.parse(String(value));
  if (Number.isNaN(ts)) return '';
  const seconds = Math.floor((Date.now() - ts) / 1000);
  if (seconds < 60) return t('chat.picker.relativeJustNow');
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return t('chat.picker.relativeMinutes', { n: minutes });
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return t('chat.picker.relativeHours', { n: hours });
  const days = Math.floor(hours / 24);
  if (days < 7) return t('chat.picker.relativeDays', { n: days });
  return new Date(ts).toLocaleDateString();
}

/** Attribute entries as `name=value`, sorted — map order is not a property of the loop. */
function attributePairs(item: TrillianInsightsDto): string[] {
  const attrs = item.worker?.attributes;
  if (attrs == null) return [];
  return Object.entries(attrs)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${k}=${v}`);
}
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <!-- ─── Toolbar ─── -->
    <div class="flex flex-wrap items-end gap-3 text-sm">
      <VButton variant="ghost" size="sm" @click="refresh">
        {{ $t('insights.trillian.refresh') }}
      </VButton>
      <div class="text-xs opacity-60 ml-auto">
        {{ $t('insights.trillian.count', { n: state.items.value.length }, state.items.value.length) }}
      </div>
    </div>

    <div v-if="state.loading.value" class="text-sm opacity-60">{{ $t('insights.trillian.loading') }}</div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <VAlert v-if="actionError" variant="error">
      {{ actionError }}
    </VAlert>

    <VEmptyState
      v-if="!state.loading.value && state.items.value.length === 0"
      :headline="$t('insights.trillian.emptyHeadline')"
      :body="$t('insights.trillian.emptyBody')"
    />

    <!-- ─── One card per pair ─── -->
    <div v-for="item in state.items.value" :key="item.control.processId" class="pair-card">
      <div class="flex flex-wrap items-center gap-2">
        <span class="font-mono text-sm font-semibold">{{ displayName(item) }}</span>
        <span class="badge-status badge-status--control">{{ item.control.processStatus }}</span>
        <span v-if="item.worker" class="badge-status badge-status--worker">{{ item.worker.status }}</span>
        <span class="badge-loops" :class="item.loopsEnabled ? 'badge-loops--on' : 'badge-loops--off'">
          {{ item.loopsEnabled ? $t('insights.trillian.loopsOn') : $t('insights.trillian.loopsOff') }}
        </span>
        <div class="ml-auto flex gap-2">
          <VButton
            size="xs"
            variant="ghost"
            :disabled="busyId === item.control.processId || !canPause(item)"
            @click="pause(item)"
          >
            {{ $t('insights.trillian.pause') }}
          </VButton>
          <VButton
            size="xs"
            variant="ghost"
            :disabled="busyId === item.control.processId || !canResume(item)"
            @click="resume(item)"
          >
            {{ $t('insights.trillian.resume') }}
          </VButton>
        </div>
      </div>

      <!-- Control side -->
      <div class="pair-grid">
        <div>
          <div class="pair-label">{{ $t('insights.trillian.control') }}</div>
          <div class="text-xs leading-5">
            <div><span class="opacity-60">{{ $t('insights.trillian.session') }}:</span> <span class="font-mono">{{ item.control.sessionId }}</span></div>
            <div><span class="opacity-60">{{ $t('insights.trillian.project') }}:</span> <span class="font-mono">{{ item.control.projectId }}</span></div>
            <div><span class="opacity-60">{{ $t('insights.trillian.nature') }}:</span> {{ item.control.nature ?? '—' }}</div>
            <div><span class="opacity-60">{{ $t('insights.trillian.created') }}:</span> {{ fmtAge(item.control.createdAt) }}</div>
          </div>
        </div>

        <!-- Worker side -->
        <div>
          <div class="pair-label">{{ $t('insights.trillian.worker') }}</div>
          <div v-if="item.worker" class="text-xs leading-5">
            <div><span class="opacity-60">{{ $t('insights.trillian.account') }}:</span> <span class="font-mono">{{ item.worker.accountId ?? '—' }}</span></div>
            <div><span class="opacity-60">{{ $t('insights.trillian.session') }}:</span> <span class="font-mono">{{ item.worker.sessionId ?? '—' }}</span></div>
            <div>
              <span class="opacity-60">{{ $t('insights.trillian.inbox') }}:</span> {{ item.worker.pendingInbox }}
            </div>
            <div v-if="attributePairs(item).length > 0" class="truncate" :title="attributePairs(item).join(', ')">
              <span class="opacity-60">{{ $t('insights.trillian.attributes') }}:</span>
              {{ attributePairs(item).join(', ') }}
            </div>
          </div>
          <div v-else class="text-xs opacity-60">{{ $t('insights.trillian.noWorker') }}</div>
        </div>
      </div>

      <!-- Task workers -->
      <div v-if="item.taskWorkers.length > 0">
        <div class="pair-label">{{ $t('insights.trillian.taskWorkers') }}</div>
        <ul class="text-xs leading-5">
          <li v-for="w in item.taskWorkers" :key="w.processId">
            <span class="font-mono">{{ w.name }}</span>
            · {{ w.status }} · {{ w.engine }}
            <span class="opacity-60">· {{ w.projectId }} · {{ fmtAge(w.createdAt) }}</span>
          </li>
        </ul>
      </div>

      <!-- Pending inbox -->
      <div v-if="item.pending.length > 0">
        <div class="pair-label">{{ $t('insights.trillian.pending') }}</div>
        <ul class="text-xs leading-5">
          <li v-for="(e, i) in item.pending" :key="`${e.taskId ?? 'm'}-${i}`">
            <span class="font-mono">{{ e.kind }}</span>
            <span v-if="e.taskId" class="opacity-60"> · {{ e.taskId }}</span>
            <span v-if="e.description"> · {{ e.description }}</span>
            <span class="opacity-60"> · {{ fmtAge(e.queuedAt) }}</span>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pair-card {
  border: 1px solid rgba(127, 127, 127, 0.25);
  border-radius: 0.5rem;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.pair-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

@media (max-width: 640px) {
  .pair-grid {
    grid-template-columns: 1fr;
  }
}

.pair-label {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  opacity: 0.55;
  margin-bottom: 0.15rem;
}

.badge-status {
  font-size: 0.7rem;
  padding: 0.1rem 0.45rem;
  border-radius: 0.375rem;
  background: rgba(127, 127, 127, 0.18);
}

.badge-status--control {
  background: rgba(59, 130, 246, 0.22);
  color: #2563eb;
}

.badge-status--worker {
  background: rgba(34, 197, 94, 0.22);
  color: #16a34a;
}

.badge-loops {
  font-size: 0.7rem;
  padding: 0.1rem 0.45rem;
  border-radius: 0.375rem;
}

.badge-loops--on {
  background: rgba(34, 197, 94, 0.22);
  color: #16a34a;
}

.badge-loops--off {
  background: rgba(239, 68, 68, 0.22);
  color: #b91c1c;
}
</style>