<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { BrainWsApi } from '@vance/shared';
import type {
  ChatMessageAppendedData,
  ChatMessageChunkData,
  ChatMessageDto,
  ChatRole,
  PlanProposedNotification,
  ProcessModeChangedNotification,
  TodoItem,
  TodosUpdatedNotification,
} from '@vance/generated';
import { useChatHistory } from '@composables/useChatHistory';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useDocumentRefStore } from '@/document/documentRefStore';
import { SessionHeader, VAlert, VButton } from '@components/index';
import MessageBubble from './MessageBubble.vue';
import FollowUpGhost from './FollowUpGhost.vue';
import PlanModeIndicator from './PlanModeIndicator.vue';
import { OPTIMISTIC_PREFIX } from './optimisticEcho';

type ProcessModeName = 'NORMAL' | 'EXPLORING' | 'PLANNING' | 'EXECUTING';

/**
 * Mirrors {@code ChatApp.MediationState}. Non-null while the bound
 * session is one Eddie switched us into. Drives the mediation banner
 * and lets the composer (sibling component) intercept the {@code /hub}
 * slash command.
 */
interface MediationState {
  workerProjectName: string;
}

const props = defineProps<{
  socket: BrainWsApi;
  sessionId: string;
  mediation?: MediationState | null;
  /** Resolved chat-process name — for filtering worker vs main-chat frames. */
  chatProcessName: string | null;
  /** Project that owns this session — used for the header label and
   *  the document-ref store. */
  chatProjectId: string;
  /** Active follow-up reply suggestion (reply mode). Rendered as a
   *  ghost bubble below the most-recent assistant message; {@code null}
   *  hides the bubble entirely. Computed by the parent so the
   *  composer (sibling) can use the same value for Space-acceptance. */
  followUpSuggestion?: string | null;
}>();

const emit = defineEmits<{
  /** User clicked "back to sessions" in the header. */
  (event: 'leave'): void;
  /** User clicked the mediation banner's "back to hub" button. */
  (event: 'hub'): void;
  /** A non-USER non-worker chat message arrived — TTS gate. */
  (event: 'speak-message', content: string): void;
  /** Any chat message arrived — talk-mode idle reset gate. */
  (event: 'note-activity'): void;
  /** The initial REST history snapshot finished loading — opens the
   *  composer's TTS gate so future frames count as live. */
  (event: 'history-loaded'): void;
  /** User clicked an ASK_USER picker option — composer should write
   *  the label into its input and immediately send. */
  (event: 'ask-user-pick', label: string): void;
  /** A {@code vance:/wizards/<name>?...} link was activated in a chat
   *  message — the right panel should switch to the wizards tab and
   *  open the named wizard with the URL prefill. */
  (event: 'wizard-deep-link', detail: { name: string; prefill: Record<string, string> }): void;
  /** Display title for {@code chatProjectId} is now known — emitted
   *  on mount once tenant projects load, and on any subsequent change.
   *  Parent uses this for the topbar breadcrumb. */
  (event: 'project-resolved', payload: { name: string; title: string }): void;
  /** User clicked the follow-up ghost bubble — parent routes this to
   *  the composer's setText + acceptCurrent. */
  (event: 'accept-follow-up'): void;
  /** Most-recent assistant message content changed (incl. {@code null}
   *  when there isn't one yet). Parent uses this to drive the
   *  follow-up suggestion fetch. */
  (event: 'last-assistant-changed', content: string | null): void;
}>();

const { t: _ } = useI18n();

const { messages: history, loading: historyLoading, error: historyError, load, reset } =
  useChatHistory();

/** Messages received via chat-message-appended after history load. Same shape as history. */
const liveMessages = ref<ChatMessageDto[]>([]);

/**
 * Set of {@code messageId}s that arrived from a sub-process (worker)
 * rather than the main chat process. The bubble for these renders in
 * the compact green worker variant of {@link MessageBubble}, mirroring
 * the foot client's {@code worker()} channel. History from REST is
 * filtered to the chat-process server-side, so this only ever fills
 * for live frames.
 */
const workerMessageIds = ref<Set<string>>(new Set());

/** Per-process buffer of streaming chunks waiting for their commit frame. */
const streamingDrafts = ref<Map<string, { role: ChatRole; content: string; processName: string }>>(
  new Map());

// ──────────────── Plan-Mode state (Arthur Plan-Mode flow) ────────────────

const chatProcessMode = ref<ProcessModeName>('NORMAL');
const chatTodos = ref<TodoItem[]>([]);
const planMeta = ref<{ version: number; summary?: string } | null>(null);

const modeBadge = computed<string | null>(() => {
  if (chatProcessMode.value === 'NORMAL') return null;
  return chatProcessMode.value.toLowerCase();
});

// ──────────────── Project label (header chip) ────────────────

const { projects: tenantProjects, reload: loadTenantProjects } = useTenantProjects();

const chatProjectLabel = computed<string>(() => {
  const id = props.chatProjectId;
  if (!id) return '';
  const p = tenantProjects.value.find((x) => x.name === id);
  const title = p?.title?.trim();
  return title && title.length > 0 ? title : id;
});

// Keep the embedded-document resolver and the save-as-document promote
// path informed about the chat's current project — both fall back to
// this store value when a vance:/-link or a kindbox action omits the
// authority segment.
const documentRefStore = useDocumentRefStore();
watch(() => props.chatProjectId, (id) => {
  documentRefStore.setCurrentProject(id);
}, { immediate: true });

// Surface the resolved project title up to ChatApp for the breadcrumb.
// The label computed above falls back to the technical id when the
// tenant project list hasn't loaded yet; we emit the resolution
// anyway so the breadcrumb at least shows something stable.
watch(chatProjectLabel, (label) => {
  if (props.chatProjectId && label) {
    emit('project-resolved', { name: props.chatProjectId, title: label });
  }
}, { immediate: true });

const messageContainer = ref<HTMLElement | null>(null);

/**
 * Combined history + live tail. Live messages are appended in arrival order.
 * If a live message has the same id as one already in history, drop the
 * duplicate (idempotent reload).
 */
const allMessages = computed<ChatMessageDto[]>(() => {
  const seen = new Set<string>();
  const result: ChatMessageDto[] = [];
  for (const m of history.value) {
    if (m.messageId && !seen.has(m.messageId)) {
      seen.add(m.messageId);
      result.push(m);
    }
  }
  for (const m of liveMessages.value) {
    if (m.messageId && !seen.has(m.messageId)) {
      seen.add(m.messageId);
      result.push(m);
    }
  }
  return result;
});

/**
 * Id of the most recent ASSISTANT message that carries
 * {@code askUserOptions} AND has no subsequent USER message — the
 * only ASK_USER picker the user can still answer by clicking.
 */
const activeAskUserMessageId = computed<string | null>(() => {
  const msgs = allMessages.value;
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i];
    if (String(m.role) === 'USER') return null;
    if (String(m.role) !== 'ASSISTANT') continue;
    const raw = m.meta?.['askUserOptions'];
    if (Array.isArray(raw) && raw.length > 0) {
      return m.messageId;
    }
  }
  return null;
});

function onPickAskUserOption(label: string): void {
  if (!label || !label.trim()) return;
  // Composer owns the send pipeline — bubble up so the parent can
  // route this to {@code composerRef.setTextAndSend(label)}.
  emit('ask-user-pick', label.trim());
}

/**
 * Most-recent ASSISTANT message that the user could plausibly reply
 * to — drives the follow-up ghost bubble. Skips streaming drafts and
 * worker messages; only fully-committed main-chat assistant messages
 * count, and only when the conversation tail isn't already a USER
 * message (i.e. the user hasn't replied yet).
 */
const lastAssistantContent = computed<string | null>(() => {
  const msgs = allMessages.value;
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i];
    if (String(m.role) === 'USER') return null;
    if (String(m.role) !== 'ASSISTANT') continue;
    if (workerMessageIds.value.has(m.messageId)) continue;
    const content = m.content?.trim();
    if (!content) return null;
    return content;
  }
  return null;
});

watch(lastAssistantContent, (next) => {
  emit('last-assistant-changed', next);
}, { immediate: true });

/** Index in {@code allMessages} of the bubble after which the
 *  follow-up ghost should be rendered. {@code -1} when there is no
 *  active follow-up. */
const followUpAnchorIndex = computed<number>(() => {
  if (!props.followUpSuggestion) return -1;
  const target = lastAssistantContent.value;
  if (!target) return -1;
  const msgs = allMessages.value;
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (String(msgs[i].role) === 'ASSISTANT' && msgs[i].content?.trim() === target) {
      return i;
    }
  }
  return -1;
});

function onAcceptFollowUp(): void {
  emit('accept-follow-up');
}

/** Sticky chat-process draft for the optimistic streaming bubble. */
const visibleDraft = computed(() => {
  if (!props.chatProcessName) return null;
  const entry = streamingDrafts.value.get(props.chatProcessName);
  if (!entry || !entry.content) return null;
  return entry;
});

const visibleWorkerDrafts = computed(() => {
  const out: Array<{ role: ChatRole; content: string; processName: string }> = [];
  for (const [name, entry] of streamingDrafts.value.entries()) {
    if (!entry.content) continue;
    if (name === props.chatProcessName) continue;
    out.push(entry);
  }
  return out;
});

function isWorkerProcess(processName: string | null | undefined): boolean {
  if (!processName) return false;
  if (!props.chatProcessName) return false;
  return processName !== props.chatProcessName;
}

function isChatProcess(processName: string | null | undefined): boolean {
  if (!processName) return false;
  if (!props.chatProcessName) return false;
  return processName === props.chatProcessName;
}

function appendMessageBubble(data: ChatMessageAppendedData): void {
  // Dedupe against optimistic local echo: when the canonical user
  // message arrives from the server, drop the matching `tmp_*` entry
  // that the composer pushed at send-time.
  const optimisticIdx = liveMessages.value.findIndex(
    (m) =>
      m.messageId.startsWith(OPTIMISTIC_PREFIX) &&
      m.role === data.role &&
      m.content === data.content,
  );
  if (optimisticIdx >= 0) {
    liveMessages.value.splice(optimisticIdx, 1);
  }
  liveMessages.value.push({
    messageId: data.chatMessageId,
    thinkProcessId: data.thinkProcessId,
    role: data.role,
    content: data.content,
    createdAt: data.createdAt,
    meta: data.meta,
  });
  if (isWorkerProcess(data.processName)) {
    workerMessageIds.value = new Set(workerMessageIds.value).add(data.chatMessageId);
  }
  streamingDrafts.value.delete(data.processName);
  // Speak non-USER messages from the main chat process when the
  // composer's speaker is enabled — sibling component, so emit up.
  if (String(data.role) !== 'USER' && !isWorkerProcess(data.processName)) {
    emit('speak-message', data.content);
  }
  // Any frame counts as activity for talk-mode's idle timer.
  emit('note-activity');
  scrollToBottom();
}

function appendChunk(data: ChatMessageChunkData): void {
  const existing = streamingDrafts.value.get(data.processName);
  if (existing && existing.role === data.role) {
    existing.content += data.chunk;
  } else {
    streamingDrafts.value.set(data.processName, {
      role: data.role,
      content: data.chunk,
      processName: data.processName,
    });
  }
  // Trigger reactivity on the Map.
  streamingDrafts.value = new Map(streamingDrafts.value);
  scrollToBottom();
}

function onProcessModeChanged(data: ProcessModeChangedNotification): void {
  if (!isChatProcess(data.processName)) return;
  const next = (data.newMode as unknown as ProcessModeName) ?? 'NORMAL';
  chatProcessMode.value = next;
  if (next === 'NORMAL') {
    chatTodos.value = [];
    planMeta.value = null;
  }
}

function onTodosUpdated(data: TodosUpdatedNotification): void {
  if (!isChatProcess(data.processName)) return;
  chatTodos.value = data.todos ?? [];
}

function onPlanProposed(data: PlanProposedNotification): void {
  if (!isChatProcess(data.processName)) return;
  planMeta.value = {
    version: data.planVersion ?? 1,
    summary: data.summary ?? undefined,
  };
}

function resetPlanModeState(): void {
  chatProcessMode.value = 'NORMAL';
  chatTodos.value = [];
  planMeta.value = null;
}

function scrollToBottom(): void {
  nextTick(() => {
    const el = messageContainer.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}

// ──────────────── Imperative API ────────────────
//
// The composer (a sibling component) drives optimistic local echoes
// via emits routed through the parent. Parent calls these methods
// imperatively on this component's ref.

function appendLocalEcho(message: ChatMessageDto): void {
  liveMessages.value.push(message);
  scrollToBottom();
}

function rollbackLocalEcho(messageId: string): void {
  const idx = liveMessages.value.findIndex((m) => m.messageId === messageId);
  if (idx >= 0) liveMessages.value.splice(idx, 1);
}

defineExpose({ appendLocalEcho, rollbackLocalEcho });

// ──────────────── Wizard deep-link plumbing ────────────────
//
// MarkdownView dispatches a 'vance-open-wizard' CustomEvent when the
// user clicks a {@code vance:/wizards/<name>?...} link. We forward it
// to the parent so it can call into the right-panel's openWizard().

function onWizardDeepLink(ev: Event): void {
  const detail = (ev as CustomEvent<{ name?: string; prefill?: Record<string, string> }>).detail;
  if (!detail || !detail.name) return;
  emit('wizard-deep-link', { name: detail.name, prefill: detail.prefill ?? {} });
}

// ──────────────── Lifecycle ────────────────

const subscriptions: Array<() => void> = [];

onMounted(async () => {
  subscriptions.push(
    props.socket.on<ChatMessageAppendedData>('chat-message-appended', appendMessageBubble),
    props.socket.on<ChatMessageChunkData>('chat-message-stream-chunk', appendChunk),
    props.socket.on<ProcessModeChangedNotification>(
      'process-mode-changed', onProcessModeChanged),
    props.socket.on<TodosUpdatedNotification>('todos-updated', onTodosUpdated),
    props.socket.on<PlanProposedNotification>('plan-proposed', onPlanProposed),
  );
  await Promise.all([
    load(props.sessionId),
    loadTenantProjects(),
  ]);
  scrollToBottom();
  // From here on, any chat-message-appended frame is by definition a
  // fresh server-side event, not a history backfill — let the composer
  // open its TTS gate.
  emit('history-loaded');
  window.addEventListener('vance-open-wizard', onWizardDeepLink);
});

onBeforeUnmount(() => {
  window.removeEventListener('vance-open-wizard', onWizardDeepLink);
  for (const off of subscriptions) off();
  reset();
});

watch(() => props.sessionId, async (newId, oldId) => {
  if (!newId || newId === oldId) return;
  liveMessages.value = [];
  workerMessageIds.value = new Set();
  streamingDrafts.value = new Map();
  resetPlanModeState();
  await load(newId);
  scrollToBottom();
});
</script>

<template>
  <div class="h-full min-h-0 flex flex-col">
    <header class="px-6 py-3 border-b border-base-300 bg-base-100 flex items-center gap-3">
      <div
        v-if="chatProjectLabel"
        class="flex items-center gap-1 text-xs px-2 py-1 rounded
               bg-base-200 text-base-content/80 max-w-[14rem] shrink-0"
        :title="$t('chat.projectTooltip', { name: chatProjectId })"
      >
        <span aria-hidden="true">📁</span>
        <span class="truncate font-medium">{{ chatProjectLabel }}</span>
      </div>
      <SessionHeader
        :session-id="sessionId"
        @archived="emit('leave')"
        @deleted="emit('leave')"
      />
      <span
        v-if="modeBadge"
        class="text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-info/15 text-info border border-info/30"
        :title="$t('chat.planMode.modeBadgeTooltip')"
      >
        {{ modeBadge }}
      </span>
    </header>

    <!-- Mediation banner — Eddie handed us over to a worker; the
         composer below sends straight to that worker's Arthur. -->
    <div
      v-if="mediation"
      class="px-6 py-2 border-b border-base-300 bg-info/10 flex items-center gap-3 text-sm"
    >
      <span class="text-base">🔗</span>
      <span class="flex-1 min-w-0 truncate">
        {{ $t('chat.mediation.banner', { project: mediation.workerProjectName }) }}
      </span>
      <VButton variant="ghost" size="sm" @click="emit('hub')">
        {{ $t('chat.mediation.backToHub') }}
      </VButton>
    </div>

    <div ref="messageContainer" class="flex-1 min-h-0 overflow-y-auto px-6 py-4">
      <div class="max-w-5xl mx-auto flex flex-col gap-3">
        <div v-if="historyLoading" class="text-sm opacity-60">
          {{ $t('chat.historyLoading') }}
        </div>
        <VAlert v-else-if="historyError" variant="error">{{ historyError }}</VAlert>

        <template v-for="(msg, idx) in allMessages" :key="msg.messageId">
          <MessageBubble
            :role="String(msg.role)"
            :content="msg.content"
            :created-at="msg.createdAt"
            :worker="workerMessageIds.has(msg.messageId)"
            :meta="msg.meta"
            :options-actionable="msg.messageId === activeAskUserMessageId"
            @pick-option="onPickAskUserOption"
          />
          <FollowUpGhost
            v-if="idx === followUpAnchorIndex && !visibleDraft"
            :suggestion="followUpSuggestion ?? null"
            @accept="onAcceptFollowUp"
          />
        </template>

        <MessageBubble
          v-if="visibleDraft"
          :role="String(visibleDraft.role)"
          :content="visibleDraft.content"
          :streaming="true"
        />

        <MessageBubble
          v-for="draft in visibleWorkerDrafts"
          :key="`worker-draft-${draft.processName}`"
          :role="String(draft.role)"
          :content="draft.content"
          :worker="true"
          :process-name="draft.processName"
          :streaming="true"
        />
      </div>
    </div>

    <PlanModeIndicator
      :mode="chatProcessMode"
      :todos="chatTodos"
      :plan-meta="planMeta"
    />
  </div>
</template>
