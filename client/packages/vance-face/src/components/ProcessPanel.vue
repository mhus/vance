<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { BrainWsApi } from '@vance/shared';
import { CloseReason } from '@vance/generated';
import type {
  ChatMessageDto,
  ProcessListRequest,
  ProcessListResponse,
  ProcessMessagesRequest,
  ProcessMessagesResponse,
  ProcessSummary,
} from '@vance/generated';
import { getSessionProcessMessages, listSessionProcesses } from '@vance/shared';
import {
  VAlert,
  VButton,
  VEmptyState,
  VIconButton,
  VInput,
  VModal,
  VSegment,
} from '@vance/components';
import MarkdownView from './MarkdownView.vue';

/**
 * Master/detail view of the session's think-processes: which ones exist,
 * what each one is saying, and the controls to steer them. Opened from the
 * process badge in the topbar.
 *
 * <p>In its default (in-session) mode everything goes over the bound
 * WebSocket — {@code process-list} for the rows and {@code process-messages}
 * for one process's conversation. Both are session-scoped server-side, which
 * is why the panel cannot show a process outside the session the client is
 * bound to (planning/process-visibility.md §5.1).
 *
 * <p>No "activate" affordance here on purpose: unlike foot, the web composer
 * has no active-process pointer — it always addresses the chat process. A
 * button that pretended otherwise would be a lie, so steering happens from
 * inside this panel instead.
 *
 * <p>Message bodies go through {@link MarkdownView}, the same renderer the
 * chat bubbles use, so a worker's fenced code, tables and {@code vance:}
 * embeds read the same here as in the conversation. That renderer pulls the
 * Pinia-backed document-ref store, which is why {@code EditorShell} only
 * mounts this panel once counts have arrived — that happens exactly in the
 * session-bearing editors (chat, cortex), and those register Pinia.
 *
 * <p><b>Preview mode.</b> With {@code sessionId} set the panel reads an
 * arbitrary session over REST ({@code sessions/{id}/processes}) instead of the
 * bound WebSocket session — that is the peek the session picker offers per
 * row, without binding (and thereby taking over) the session. Steering and
 * lifecycle act on the lane of the bound session, so they are hidden there;
 * the way to steer is to open the session.
 */
interface Props {
  modelValue: boolean;
  socket?: BrainWsApi | null;
  /** Preview a foreign session over REST instead of the bound one. */
  sessionId?: string | null;
  /** Human label for the previewed session, shown in the header. */
  sessionLabel?: string | null;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  (e: 'open-session', sessionId: string): void;
}>();

/** REST-backed read-only view of a session the client is not bound to. */
const preview = computed(() => Boolean(props.sessionId));

const { t } = useI18n();

const rows = ref<ProcessSummary[]>([]);
/**
 * Terminated processes are history, not noise — the panel opens with
 * "all". That is the only way an ARCHIVED session shows anything at all:
 * the archive cascade closes every process (closeReason=ARCHIVED), so a
 * live-only default would render the picker's 🧵-preview of archived
 * sessions permanently empty. The toggle narrows to live when wanted.
 */
const includeTerminated = ref(true);
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

// {@code immediate} matters for the preview instance: the picker sets its
// session and opens the panel in the same tick, so the component mounts with
// {@code modelValue} already true and a change-only watcher would never load.
watch(() => props.modelValue, (open) => {
  if (open) {
    selected.value = null;
    detail.value = null;
    actionNote.value = null;
    void loadList();
  }
}, { immediate: true });

async function loadList(): Promise<void> {
  if (!preview.value && !props.socket) {
    error.value = t('processPanel.noConnection');
    rows.value = [];
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const response = preview.value
      ? await listSessionProcesses(props.sessionId as string, includeTerminated.value)
      : await props.socket!.send<ProcessListRequest, ProcessListResponse>(
        'process-list',
        { includeTerminated: includeTerminated.value },
      );
    rows.value = response?.processes ?? [];
    autoSelect();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('processPanel.loadFailed');
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

/**
 * Open the first row when nothing (still) is selected. The session's chat /
 * orchestrator process is created at bootstrap and therefore leads the list,
 * so the panel opens on the process the user almost always means — and the
 * empty "pick a process" pane stops being the default view.
 *
 * <p>An existing selection survives a reload; it only gives way when the row
 * disappeared (e.g. the live filter dropped a closed process).
 */
function autoSelect(): void {
  const current = selected.value;
  if (current && rows.value.some((r) => r.id === current.id)) return;
  selected.value = null;
  detail.value = null;
  const first = rows.value[0];
  if (first) void openDetail(first);
}

async function openDetail(row: ProcessSummary): Promise<void> {
  selected.value = row;
  detail.value = null;
  detailError.value = null;
  actionNote.value = null;
  steerText.value = '';
  if (!preview.value && !props.socket) return;
  detailLoading.value = true;
  try {
    detail.value = preview.value
      ? await getSessionProcessMessages(props.sessionId as string, row.name, 100)
      : await props.socket!.send<ProcessMessagesRequest, ProcessMessagesResponse>(
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

/** Terminal rows keep their audit trail on the document — see {@link CloseReason}. */
function isClosed(row: ProcessSummary | ProcessMessagesResponse): boolean {
  return String(row.status ?? '') === 'CLOSED';
}

const KNOWN_CLOSE_REASONS = new Set<string>(Object.values(CloseReason));

/**
 * Human label for a process's close reason, marked specially on terminal
 * rows — "archiviert" tells the history-reading user why a process is
 * closed instead of leaving them guessing at a bare "closed".
 */
function closeReasonLabel(row: ProcessSummary | ProcessMessagesResponse): string {
  const reason = row.closeReason;
  if (!reason) return '';
  return KNOWN_CLOSE_REASONS.has(reason)
    ? t(`processPanel.closeReason.${reason}`)
    : reason;
}

/** Detail header: status plus close reason when the process is terminal. */
const detailStatus = computed(() => {
  const row = detail.value ?? selected.value;
  if (!row) return '';
  const reason = closeReasonLabel(row);
  return reason ? `${statusOf(row)} · ${reason}` : statusOf(row);
});

function roleOf(msg: ChatMessageDto): string {
  return String(msg.role ?? '').toLowerCase();
}

type ListFilter = 'live' | 'all';

const listFilter = computed<ListFilter>(() => (includeTerminated.value ? 'all' : 'live'));

const listFilterOptions = computed(() => [
  { value: 'live' as const, label: t('processPanel.segmentLive') },
  { value: 'all' as const, label: t('processPanel.segmentAll') },
]);

async function setListFilter(filter: ListFilter): Promise<void> {
  const next = filter === 'all';
  if (next === includeTerminated.value) return;
  includeTerminated.value = next;
  await loadList();
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    :title="preview && sessionLabel
      ? t('processPanel.previewTitle', { session: sessionLabel })
      : t('processPanel.title')"
    size="xl"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <VAlert v-if="error" variant="error" class="mb-4">{{ error }}</VAlert>

    <div class="flex gap-4 min-h-[24rem]">
      <!-- Master list -->
      <div class="w-1/3 min-w-[14rem] flex flex-col gap-2">
        <div class="flex items-center justify-between gap-2">
          <span class="text-xs text-(--v-label-secondary)">
            {{ t('processPanel.count', { n: rows.length }) }}
          </span>
          <VSegment
            :model-value="listFilter"
            :options="listFilterOptions"
            :aria-label="t('processPanel.filterLabel')"
            @update:model-value="setListFilter"
          />
        </div>

        <VEmptyState v-if="!hasRows && !loading" :headline="t('processPanel.empty')" />

        <ul v-else class="flex flex-col gap-0.5 overflow-y-auto">
          <li v-for="row in rows" :key="row.id">
            <button
              type="button"
              class="process-row"
              :class="{
                'process-row-selected': selected?.id === row.id,
                // Terminal rows are history — keep them visible, but visually
                // subordinate to what is actually running.
                'process-row-closed': isClosed(row),
              }"
              @click="openDetail(row)"
            >
              <div class="flex items-center gap-2">
                <span class="font-mono text-xs truncate">{{ row.name }}</span>
                <span class="text-[10px] text-(--v-label-secondary)">{{ statusOf(row) }}</span>
                <span
                  v-if="isClosed(row) && closeReasonLabel(row)"
                  class="text-[10px] px-1 rounded bg-(--v-fill) shrink-0"
                >{{ closeReasonLabel(row) }}</span>
              </div>
              <div class="text-[10px] text-(--v-label-secondary) truncate">
                {{ row.thinkEngine }}<template v-if="row.goal"> · {{ row.goal }}</template>
              </div>
            </button>
          </li>
        </ul>
      </div>

      <!-- Detail -->
      <div class="flex-1 min-w-0 flex flex-col gap-2 border-l border-(--v-separator) pl-4">
        <VEmptyState v-if="!selected" :headline="t('processPanel.pickOne')" />

        <template v-else>
          <div class="flex items-center gap-2">
            <div class="flex-1 min-w-0 text-xs text-(--v-label-secondary)">
              <span class="font-mono">{{ selected.name }}</span>
              · {{ selected.thinkEngine }}
              · {{ detailStatus }}
            </div>
            <VIconButton
              icon="refresh"
              size="sm"
              :label="t('processPanel.reload')"
              :disabled="busy"
              @click="openDetail(selected)"
            />
          </div>
          <p v-if="selected.goal" class="text-xs text-(--v-label-secondary)">{{ selected.goal }}</p>

          <VAlert v-if="detailError" variant="error">{{ detailError }}</VAlert>
          <VAlert v-if="actionNote" variant="info">{{ actionNote }}</VAlert>

          <div class="flex-1 overflow-y-auto text-sm flex flex-col gap-2">
            <p v-if="detailLoading" class="text-(--v-label-secondary)">{{ t('processPanel.loading') }}</p>
            <p
              v-else-if="detail && (detail.messages?.length ?? 0) === 0"
              class="text-(--v-label-secondary)"
            >
              {{ t('processPanel.silent') }}
            </p>
            <template v-else-if="detail">
              <p v-if="detail.olderTruncated" class="text-xs text-(--v-label-tertiary)">
                {{ t('processPanel.olderTruncated', { n: detail.olderTruncated }) }}
              </p>
              <div v-for="msg in detail.messages" :key="msg.messageId" class="flex flex-col">
                <span class="text-[10px] uppercase text-(--v-label-tertiary)">{{ roleOf(msg) }}</span>
                <MarkdownView :source="msg.content ?? ''" />
              </div>
            </template>
          </div>

          <!-- Composer: field and its send button form one flush row. -->
          <div v-if="!preview" class="flex items-center gap-2">
            <div class="flex-1 min-w-0">
              <VInput
                v-model="steerText"
                :placeholder="t('processPanel.steerPlaceholder')"
                :disabled="busy"
                @keydown.enter="steer"
              />
            </div>
            <VButton
              variant="primary"
              :disabled="busy || !steerText.trim()"
              @click="steer"
            >
              {{ t('processPanel.steer') }}
            </VButton>
          </div>
        </template>
      </div>
    </div>

    <template v-if="selected" #actions>
      <!-- Preview mode: read-only. Steering and lifecycle need the
           session bound, so the only action offered is opening it. -->
      <template v-if="preview">
        <span class="mr-auto text-xs text-(--v-label-secondary)">
          {{ t('processPanel.previewReadOnly') }}
        </span>
        <VButton variant="secondary" @click="emit('open-session', sessionId as string)">
          {{ t('processPanel.openSession') }}
        </VButton>
      </template>
      <template v-else>
        <VButton variant="secondary" :disabled="busy" @click="lifecycle('process-pause')">
          {{ t('processPanel.pause') }}
        </VButton>
        <VButton variant="secondary" :disabled="busy" @click="lifecycle('process-resume')">
          {{ t('processPanel.resume') }}
        </VButton>
        <VButton variant="danger" :disabled="busy" @click="lifecycle('process-stop')">
          {{ t('processPanel.stop') }}
        </VButton>
      </template>
    </template>
  </VModal>
</template>

<style scoped>
/* Master-list row (corporate-design.md §7.3): hover = fill at 50 %,
   selection = the selection token with accent text. */
.process-row {
  display: block;
  width: 100%;
  padding: 4px 8px;
  border-radius: var(--v-radius-control);
  text-align: left;
  transition: background-color var(--v-duration, 150ms) ease-out;
}
.process-row:hover { background: color-mix(in srgb, var(--v-fill) 50%, transparent); }
.process-row-selected,
.process-row-selected:hover {
  background: var(--v-selection);
  color: var(--v-accent-text);
}
.process-row-closed { color: var(--v-label-secondary); }
</style>
