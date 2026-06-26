<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { BrainWsApi } from '@vance/shared';
import type {
  ChatMessageAppendedData,
  ChatMessageChunkData,
  ChatMessageDto,
  ChatRole,
  DocumentDto,
  PlanProposedNotification,
  ProcessModeChangedNotification,
  TodoItem,
  TodosUpdatedNotification,
} from '@vance/generated';
import { useChatHistory } from '@composables/useChatHistory';
import { useConversationExport } from '@composables/useConversationExport';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useDocumentRefStore } from '@/document/documentRefStore';
import { SessionHeader, VAlert, VButton } from '@components/index';
import { getUsername } from '@vance/shared';
import {
  useSessionRoster,
  type RosterChange,
} from '@/cortex/composables/useSessionRoster';
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
  /** A chat-export document was created (success). Cortex listens to
   *  this and opens the document as a new tab; the chat editor itself
   *  shows a transient banner via its internal {@code exportFeedback}
   *  state. */
  (event: 'conversation-exported', payload: { documentId: string; document: DocumentDto }): void;
}>();

const { t: _ } = useI18n();

/**
 * Authenticated user of this tab — used by {@link MessageBubble} to
 * decide whether a USER bubble is mine (right-side / primary
 * colour) or someone else's (left-side / accent colour with name
 * header) in a multi-user session. See
 * planning/multi-user-sessions.md §6.
 */
const currentUserId = computed<string | null>(() => getUsername());

/**
 * Ephemeral participant activity feed — see
 * planning/multi-user-sessions.md §7. Roster join/leave deltas are
 * rendered as a thin separator below the chat messages, NOT
 * persisted as ChatMessageDocuments (would flap on reconnects and
 * waste prompt tokens). The session-roster baseline at attach time
 * is silently swallowed so a fresh page load doesn't spam "X joined"
 * for everyone already present.
 */
interface ActivityEvent {
  id: string;
  kind: 'joined' | 'left' | 'who';
  /** Single display name for joined/left, full participant list (joined as
   *  comma-separated string) for 'who'. */
  displayName: string;
  at: Date;
}
const activityEvents = ref<ActivityEvent[]>([]);
let activitySeq = 0;
const sessionIdRef = computed(() => props.sessionId);
const { onChange: onRosterChange, onInitial: onRosterInitial } =
  useSessionRoster(sessionIdRef);
// On (re-)attach to a shared session, surface the current roster as
// a "currently here" activity line — same render as the /who slash
// command output, but triggered automatically.
onRosterInitial((list) => {
  if (list.length === 0) return;
  const names = list
    .map((p) => p.displayName?.trim() || p.userId)
    .filter((n): n is string => Boolean(n));
  if (names.length === 0) return;
  activityEvents.value.push({
    id: `act-${++activitySeq}`,
    kind: 'who',
    displayName: names.join(', '),
    at: new Date(),
  });
});
onRosterChange((change: RosterChange) => {
  for (const p of change.joined) {
    activityEvents.value.push({
      id: `act-${++activitySeq}`,
      kind: 'joined',
      displayName: p.displayName ?? p.userId,
      at: change.at,
    });
  }
  for (const p of change.left) {
    activityEvents.value.push({
      id: `act-${++activitySeq}`,
      kind: 'left',
      displayName: p.displayName ?? p.userId,
      at: change.at,
    });
  }
});
// Reset the ephemeral feed when the chat-view is rebound to a fresh
// session — otherwise the previous session's activity would bleed
// into the next one.
watch(
  () => props.sessionId,
  () => {
    activityEvents.value = [];
  },
);

/**
 * Pushes a "who is here right now" activity line — called by the
 * parent (ChatApp) after a successful {@code session-who} WS reply.
 * Exposed via {@link defineExpose} below.
 */
function pushWhoActivity(names: string[]): void {
  activityEvents.value.push({
    id: `act-${++activitySeq}`,
    kind: 'who',
    displayName: names.join(', '),
    at: new Date(),
  });
}


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

// Drop the project chip when the chat header gets cramped. The chip
// eats ~224px of horizontal real estate (plus padding/gap), which is
// the dominant culprit pushing SessionHeader into its overflow menu.
// Hiding it here gives SessionHeader the breathing room to stay in
// wide mode a bit longer; SessionHeader still has its own internal
// collapse below ~520px once the chip is gone.
const HEADER_DENSE_THRESHOLD_PX = 800;
const headerEl = ref<HTMLElement | null>(null);
const headerWidth = ref<number>(Number.POSITIVE_INFINITY);
const headerDense = computed(() => headerWidth.value < HEADER_DENSE_THRESHOLD_PX);
let headerResizeObserver: ResizeObserver | null = null;

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
    senderUserId: data.senderUserId,
    senderDisplayName: data.senderDisplayName,
    addressedToAgent: data.addressedToAgent,
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

defineExpose({ appendLocalEcho, rollbackLocalEcho, pushWhoActivity });

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

function subscribeToSocket(): void {
  // Replace any previous subscriptions in-place — used both on initial
  // mount and after ChatApp swaps in a fresh socket via ensureConnected
  // (server-side idle close followed by reconnect-on-send). The
  // {@code socket} prop ref changes, our subscribers are still bound
  // to the dead instance, hence the re-attach.
  for (const off of subscriptions) off();
  subscriptions.length = 0;
  subscriptions.push(
    props.socket.on<ChatMessageAppendedData>('chat-message-appended', appendMessageBubble),
    props.socket.on<ChatMessageChunkData>('chat-message-stream-chunk', appendChunk),
    props.socket.on<ProcessModeChangedNotification>(
      'process-mode-changed', onProcessModeChanged),
    props.socket.on<TodosUpdatedNotification>('todos-updated', onTodosUpdated),
    props.socket.on<PlanProposedNotification>('plan-proposed', onPlanProposed),
  );
}

watch(() => props.socket, (next, prev) => {
  if (next === prev) return;
  subscribeToSocket();
});

onMounted(async () => {
  subscribeToSocket();
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

  if (headerEl.value) {
    headerWidth.value = headerEl.value.offsetWidth;
    headerResizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        headerWidth.value = entry.contentRect.width;
      }
    });
    headerResizeObserver.observe(headerEl.value);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('vance-open-wizard', onWizardDeepLink);
  headerResizeObserver?.disconnect();
  headerResizeObserver = null;
  for (const off of subscriptions) off();
  reset();
});

watch(() => props.sessionId, async (newId, oldId) => {
  if (!newId || newId === oldId) return;
  liveMessages.value = [];
  workerMessageIds.value = new Set();
  streamingDrafts.value = new Map();
  resetPlanModeState();
  exportFeedback.value = null;
  if (exportFeedbackTimer) {
    clearTimeout(exportFeedbackTimer);
    exportFeedbackTimer = null;
  }
  await load(newId);
  scrollToBottom();
});

// ──────────────── Save conversation as document ────────────────
//
// User-facing affordance: the chat header offers a "Save as document"
// button. The button writes a Markdown file under
// `conversations/chat-{ts}.md` in the chat's own project, with
// `autoSummary=false` and `ragEnabled='off'` — the conversation itself
// is already indexed for the session, so re-summarising / re-embedding
// the export would just duplicate work. The cortex editor additionally
// opens the resulting document as a new tab; the chat editor only
// shows a transient banner.

const { saveConversationAsDocument } = useConversationExport();
const exporting = ref(false);
const exportFeedback = ref<{ kind: 'success' | 'error'; message: string } | null>(null);
let exportFeedbackTimer: ReturnType<typeof setTimeout> | null = null;

/** Filtered list of turns that would actually contribute to the export.
 *  Mirrors {@code useConversationExport}'s role-filter so the button
 *  greys out when there is nothing exportable (empty session, only
 *  worker side-chatter, only SYSTEM messages). */
const exportableTurns = computed<ChatMessageDto[]>(() =>
  allMessages.value.filter((m) => {
    if (workerMessageIds.value.has(m.messageId)) return false;
    const role = String(m.role);
    if (role !== 'USER' && role !== 'ASSISTANT') return false;
    return (m.content?.trim().length ?? 0) > 0;
  }),
);

const canExportConversation = computed(() => exportableTurns.value.length > 0);

async function onSaveConversation(): Promise<void> {
  if (exporting.value || !canExportConversation.value) return;
  if (!props.chatProjectId) return;
  exporting.value = true;
  exportFeedback.value = null;
  try {
    const doc = await saveConversationAsDocument({
      projectId: props.chatProjectId,
      sessionId: props.sessionId,
      turns: exportableTurns.value,
    });
    if (!doc) {
      exportFeedback.value = {
        kind: 'error',
        message: _('chat.export.saveFailed'),
      };
    } else {
      exportFeedback.value = {
        kind: 'success',
        message: _('chat.export.saveSucceeded', { path: doc.path }),
      };
      emit('conversation-exported', { documentId: doc.id, document: doc });
    }
  } catch (e) {
    exportFeedback.value = {
      kind: 'error',
      message: e instanceof Error ? e.message : _('chat.export.saveFailed'),
    };
  } finally {
    exporting.value = false;
    if (exportFeedbackTimer) clearTimeout(exportFeedbackTimer);
    exportFeedbackTimer = setTimeout(() => {
      exportFeedback.value = null;
      exportFeedbackTimer = null;
    }, 5000);
  }
}

onBeforeUnmount(() => {
  if (exportFeedbackTimer) {
    clearTimeout(exportFeedbackTimer);
    exportFeedbackTimer = null;
  }
});
</script>

<template>
  <div class="h-full min-h-0 flex flex-col">
    <header ref="headerEl" class="px-6 py-3 border-b border-base-300 bg-base-100 flex items-center gap-3">
      <div
        v-if="chatProjectLabel && !headerDense"
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
      <VButton
        variant="ghost"
        size="sm"
        :disabled="exporting || !canExportConversation"
        :title="$t('chat.export.saveAsDocumentTooltip')"
        :aria-label="$t('chat.export.saveAsDocumentTooltip')"
        @click="onSaveConversation"
      >{{ exporting ? '⌛' : '💾' }}</VButton>
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
        <VAlert
          v-if="exportFeedback"
          :variant="exportFeedback.kind"
        >{{ exportFeedback.message }}</VAlert>
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
            :sender-user-id="msg.senderUserId"
            :sender-display-name="msg.senderDisplayName"
            :current-user-id="currentUserId"
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

        <!-- Ephemeral roster activity — non-persistent, see
             planning/multi-user-sessions.md §7. Each join/leave gets
             a thin centered separator; page reload wipes the feed. -->
        <div
          v-for="evt in activityEvents"
          :key="evt.id"
          class="flex items-center gap-2 text-xs opacity-60 my-2"
        >
          <div class="flex-1 border-t border-base-300" />
          <span v-if="evt.kind === 'who'">
            <span aria-hidden="true">👥</span>
            <span class="ml-1">{{ _('chat.activity.whoHeader') }}</span>
            <span class="font-medium ml-1">{{ evt.displayName }}</span>
          </span>
          <span v-else>
            <span aria-hidden="true">👥</span>
            <span class="font-medium ml-1">{{ evt.displayName }}</span>
            <span class="ml-1">{{
              evt.kind === 'joined'
                ? _('chat.activity.joined')
                : _('chat.activity.left')
            }}</span>
          </span>
          <div class="flex-1 border-t border-base-300" />
        </div>
      </div>
    </div>

    <PlanModeIndicator
      :mode="chatProcessMode"
      :todos="chatTodos"
      :plan-meta="planMeta"
    />
  </div>
</template>
