<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { BrainWsApi } from '@vance/shared';
import type {
  ChatMessageDto,
  ProcessListRequest,
  ProcessListResponse,
  ProcessMessagesRequest,
  ProcessMessagesResponse,
  ProcessSummary,
} from '@vance/generated';
import { VAlert, VButton, VEmptyState, VInput, VModal } from '@vance/components';

/**
 * Master/detail view of the session's think-processes: which ones exist,
 * what each one is saying, and the controls to steer them. Opened from the
 * process badge in the topbar.
 *
 * <p>Everything goes over the bound WebSocket — {@code process-list} for the
 * rows and {@code process-messages} for one process's conversation. The
 * latter is session-scoped server-side, which is why the panel cannot show a
 * process outside the session the client is bound to
 * (planning/process-visibility.md §5.1).
 *
 * <p>No "activate" affordance here on purpose: unlike foot, the web composer
 * has no active-process pointer — it always addresses the chat process. A
 * button that pretended otherwise would be a lie, so steering happens from
 * inside this panel instead.
 */
interface Props {
  modelValue: boolean;
  socket: BrainWsApi | null;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
}>();

const { t } = useI18n();

const rows = ref<ProcessSummary[]>([]);
const includeTerminated = ref(false);
const loading = ref(false);
const error = ref<string | null>(null);

const selected = ref<ProcessSummary | null>(null);
const detail = ref<ProcessMessagesResponse | null>(null);
const detailLoading = ref(false);
const detailError = ref<string | null>(null);
const steerText = ref('');
const busy = ref(false);
const actionNote = ref<string | null>(null);

const hasRows = computed(() => rows.value.length > 0);

watch(() => props.modelValue, (open) => {
  if (open) {
    selected.value = null;
    detail.value = null;
    actionNote.value = null;
    void loadList();
  }
});

async function loadList(): Promise<void> {
  if (!props.socket) {
    error.value = t('processPanel.noConnection');
    rows.value = [];
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const response = await props.socket.send<ProcessListRequest, ProcessListResponse>(
      'process-list',
      { includeTerminated: includeTerminated.value },
    );
    rows.value = response?.processes ?? [];
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('processPanel.loadFailed');
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

async function openDetail(row: ProcessSummary): Promise<void> {
  selected.value = row;
  detail.value = null;
  detailError.value = null;
  actionNote.value = null;
  steerText.value = '';
  if (!props.socket) return;
  detailLoading.value = true;
  try {
    detail.value = await props.socket.send<ProcessMessagesRequest, ProcessMessagesResponse>(
      'process-messages',
      { name: row.name, limit: 100 },
    );
  } catch (e) {
    detailError.value = e instanceof Error ? e.message : t('processPanel.loadFailed');
  } finally {
    detailLoading.value = false;
  }
}

async function steer(): Promise<void> {
  const row = selected.value;
  const text = steerText.value.trim();
  if (!row || !text || !props.socket || busy.value) return;
  busy.value = true;
  actionNote.value = null;
  try {
    await props.socket.send('process-steer', { processName: row.name, content: text });
    steerText.value = '';
    // Honest about the lane: a running process takes the message but acts
    // on it only after its current turn (§5.2).
    actionNote.value = String(row.status) === 'RUNNING'
      ? t('processPanel.queuedBusy')
      : t('processPanel.sent');
    await openDetail(row);
  } catch (e) {
    detailError.value = e instanceof Error ? e.message : t('processPanel.actionFailed');
  } finally {
    busy.value = false;
  }
}

async function lifecycle(type: 'process-pause' | 'process-resume' | 'process-stop'): Promise<void> {
  const row = selected.value;
  if (!row || !props.socket || busy.value) return;
  busy.value = true;
  actionNote.value = null;
  try {
    await props.socket.send(type, { processName: row.name });
    actionNote.value = t('processPanel.done');
    await Promise.all([loadList(), openDetail(row)]);
  } catch (e) {
    detailError.value = e instanceof Error ? e.message : t('processPanel.actionFailed');
  } finally {
    busy.value = false;
  }
}

function statusOf(row: ProcessSummary | ProcessMessagesResponse): string {
  return String(row.status ?? '?').toLowerCase();
}

function roleOf(msg: ChatMessageDto): string {
  return String(msg.role ?? '').toLowerCase();
}

async function toggleTerminated(): Promise<void> {
  includeTerminated.value = !includeTerminated.value;
  await loadList();
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    :title="t('processPanel.title')"
    size="xl"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <VAlert v-if="error" variant="error" class="mb-3">{{ error }}</VAlert>

    <div class="flex gap-4 min-h-[24rem]">
      <!-- Master list -->
      <div class="w-1/3 min-w-[14rem] flex flex-col gap-2">
        <div class="flex items-center justify-between gap-2">
          <span class="text-xs opacity-70">
            {{ t('processPanel.count', { n: rows.length }) }}
          </span>
          <VButton size="xs" variant="ghost" @click="toggleTerminated">
            {{ includeTerminated ? t('processPanel.filterAll') : t('processPanel.filterLive') }}
          </VButton>
        </div>

        <VEmptyState v-if="!hasRows && !loading" :headline="t('processPanel.empty')" />

        <ul v-else class="flex flex-col gap-1 overflow-y-auto">
          <li v-for="row in rows" :key="row.id">
            <button
              type="button"
              class="w-full text-left px-2 py-1 rounded"
              :class="selected?.id === row.id ? 'bg-base-300' : 'hover:bg-base-200'"
              @click="openDetail(row)"
            >
              <div class="flex items-center gap-2">
                <span class="font-mono text-xs truncate">{{ row.name }}</span>
                <span class="text-[10px] opacity-60">{{ statusOf(row) }}</span>
              </div>
              <div class="text-[10px] opacity-60 truncate">
                {{ row.thinkEngine }}<template v-if="row.goal"> · {{ row.goal }}</template>
              </div>
            </button>
          </li>
        </ul>
      </div>

      <!-- Detail -->
      <div class="flex-1 flex flex-col gap-2 border-l border-base-300 pl-4">
        <VEmptyState v-if="!selected" :headline="t('processPanel.pickOne')" />

        <template v-else>
          <div class="text-xs opacity-70">
            <span class="font-mono">{{ selected.name }}</span>
            · {{ selected.thinkEngine }}
            · {{ statusOf(detail ?? selected) }}
          </div>
          <p v-if="selected.goal" class="text-xs opacity-60">{{ selected.goal }}</p>

          <VAlert v-if="detailError" variant="error">{{ detailError }}</VAlert>
          <VAlert v-if="actionNote" variant="info">{{ actionNote }}</VAlert>

          <div class="flex-1 overflow-y-auto text-sm flex flex-col gap-2">
            <p v-if="detailLoading" class="opacity-60">{{ t('processPanel.loading') }}</p>
            <p v-else-if="detail && (detail.messages?.length ?? 0) === 0" class="opacity-60">
              {{ t('processPanel.silent') }}
            </p>
            <template v-else-if="detail">
              <p v-if="detail.olderTruncated" class="text-xs opacity-50">
                {{ t('processPanel.olderTruncated', { n: detail.olderTruncated }) }}
              </p>
              <div v-for="msg in detail.messages" :key="msg.messageId" class="flex flex-col">
                <span class="text-[10px] uppercase opacity-50">{{ roleOf(msg) }}</span>
                <span class="whitespace-pre-wrap">{{ msg.content }}</span>
              </div>
            </template>
          </div>

          <div class="flex items-center gap-2">
            <VInput
              v-model="steerText"
              :placeholder="t('processPanel.steerPlaceholder')"
              :disabled="busy"
              class="flex-1"
              @keydown.enter="steer"
            />
            <VButton size="sm" :disabled="busy || !steerText.trim()" @click="steer">
              {{ t('processPanel.steer') }}
            </VButton>
          </div>

          <div class="flex items-center gap-2">
            <VButton size="xs" variant="ghost" :disabled="busy" @click="lifecycle('process-pause')">
              {{ t('processPanel.pause') }}
            </VButton>
            <VButton size="xs" variant="ghost" :disabled="busy" @click="lifecycle('process-resume')">
              {{ t('processPanel.resume') }}
            </VButton>
            <VButton size="xs" variant="ghost" :disabled="busy" @click="lifecycle('process-stop')">
              {{ t('processPanel.stop') }}
            </VButton>
            <VButton size="xs" variant="ghost" :disabled="busy" @click="openDetail(selected)">
              {{ t('processPanel.reload') }}
            </VButton>
          </div>
        </template>
      </div>
    </div>
  </VModal>
</template>
