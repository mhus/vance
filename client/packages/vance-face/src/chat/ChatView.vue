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
  ProcessProgressNotification,
  TodoItem,
  TodosUpdatedNotification,
} from '@vance/generated';
import { useChatHistory } from '@composables/useChatHistory';
import { useConversationExport } from '@composables/useConversationExport';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useDocumentRefStore } from '@/kindViews/documentRefStore';
import { SessionHeader, VAlert, VButton } from '@components/index';
import { getUsername } from '@vance/shared';
import {
  useSessionRoster,
  type RosterChange,
} from '@/cortex/composables/useSessionRoster';
import MessageBubble from './MessageBubble.vue';
import FollowUpGhost from './FollowUpGhost.vue';
import PlanModeIndicator from './PlanModeIndicator.vue';
import ChatActivityStrip from './ChatActivityStrip.vue';
import { applyProgress, createActivityState } from './chatActivity';
import { OPTIMISTIC_PREFIX } from './optimisticEcho';
import { buildFollowUpContext, type FollowUpContext } from './followUpContext';

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
  /** The bounded main-chat transcript and its final message changed.
   *  Parent uses this to drive the follow-up suggestion fetch. */
  (event: 'follow-up-context-changed', payload: FollowUpContext | null): void;
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
  kind: 'joined' | 'left' | 'who' | 'command';
  /** Single display name for joined/left, full participant list (joined as
   *  comma-separated string) for 'who', pre-rendered result line for
   *  'command' (the `//verb` engine-command feedback). */
  displayName: string;
  at: Date;
  /** Message after which this event should render — snapshotted to the
   *  last message in {@link allMessages} at push time. {@code null}
   *  means "render above all messages" (event arrived before any
   *  message did). Pinning to a messageId keeps the event inline in
   *  the chat flow instead of sliding down each time a new bubble
   *  appears at the bottom. */
  afterMessageId: string | null;
}
const activityEvents = ref<ActivityEvent[]>([]);
let activitySeq = 0;

function lastMessageIdSnapshot(): string | null {
  const msgs = allMessages.value;
  for (let i = msgs.length - 1; i >= 0; i--) {
    const id = msgs[i].messageId;
    if (id) return id;
  }
  return null;
}
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
    afterMessageId: lastMessageIdSnapshot(),
  });
});
onRosterChange((change: RosterChange) => {
  const anchor = lastMessageIdSnapshot();
  for (const p of change.joined) {
    activityEvents.value.push({
      id: `act-${++activitySeq}`,
      kind: 'joined',
      displayName: p.displayName ?? p.userId,
      at: change.at,
      afterMessageId: anchor,
    });
  }
  for (const p of change.left) {
    activityEvents.value.push({
      id: `act-${++activitySeq}`,
      kind: 'left',
      displayName: p.displayName ?? p.userId,
      at: change.at,
      afterMessageId: anchor,
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
/**
 * Activity events grouped by their anchor messageId. Used by the
 * template to interleave roster join/leave/who lines into the message
 * stream right where they arrived, instead of dumping them all at the
 * bottom (which made the feed slide down whenever a new bubble landed).
 * Key {@code ''} holds the leading bucket — events that arrived before
 * any message did.
 */
const activityEventsByAnchor = computed<Map<string, ActivityEvent[]>>(() => {
  const map = new Map<string, ActivityEvent[]>();
  for (const evt of activityEvents.value) {
    const key = evt.afterMessageId ?? '';
    let bucket = map.get(key);
    if (!bucket) {
      bucket = [];
      map.set(key, bucket);
    }
    bucket.push(evt);
  }
  return map;
});
const leadingActivityEvents = computed<ActivityEvent[]>(
  () => activityEventsByAnchor.value.get('') ?? [],
);
function activityEventsAfter(messageId: string | undefined): ActivityEvent[] {
  if (!messageId) return [];
  return activityEventsByAnchor.value.get(messageId) ?? [];
}

function pushWhoActivity(names: string[]): void {
  activityEvents.value.push({
    id: `act-${++activitySeq}`,
    kind: 'who',
    displayName: names.join(', '),
    at: new Date(),
    afterMessageId: lastMessageIdSnapshot(),
  });
}

/**
 * Pushes a {@code //verb} engine-command result as an ephemeral line —
 * called by the parent (ChatApp) after a {@code process-command} reply.
 * The {@code line} is pre-rendered (e.g. {@code "// echo → ok: …"}).
 * Exposed via {@link defineExpose} below.
 */
function pushCommandActivity(line: string): void {
  activityEvents.value.push({
    id: `act-${++activitySeq}`,
    kind: 'command',
    displayName: line,
    at: new Date(),
    afterMessageId: lastMessageIdSnapshot(),
  });
}


const { messages: history, loading: historyLoading, error: historyError, load, reset } =
  useChatHistory();

/** Messages received via chat-message-appended after history load. Same shape as history. */
const liveMessages = ref<ChatMessageDto[]>([]);

/**
 * Whether a message came from a sub-process (worker) rather than the main
 * chat process. Its bubble renders in the compact green worker variant of
 * {@link MessageBubble}, mirroring the foot client's {@code worker()}
 * channel.
 *
 * <p>Derived from {@code processName}, which both the live
 * {@code chat-message-appended} frame and the REST history carry — so a
 * reload keeps the worker notes looking like notes instead of promoting them
 * to main-chat turns (planning/process-visibility.md §5.3). Being a computed
 * derivation rather than a tracked Set also means it self-corrects once
 * {@code chatProcessName} arrives from the bootstrap response.
 */
function isWorkerMessage(msg: ChatMessageDto): boolean {
  return isWorkerProcess(msg.processName);
}

/** Per-process buffer of streaming chunks waiting for their commit frame.
 *  `thinking` accumulates the reasoning side-channel, which streams before
 *  the answer content. */
const streamingDrafts = ref<
  Map<string, { role: ChatRole; content: string; thinking: string; processName: string }>
>(new Map());

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
 * Bounded, speaker-aware transcript of the committed main conversation.
 * Roles need not alternate and USER turns retain their display names for
 * shared sessions. Worker side-channel messages are deliberately excluded.
 */
const followUpContext = computed<FollowUpContext | null>(() => {
  return buildFollowUpContext(allMessages.value.filter((message) => !isWorkerMessage(message)));
});

watch(followUpContext, (next) => {
  emit('follow-up-context-changed', next);
}, { immediate: true });

/** Index in {@code allMessages} of the exact message after which the
 *  follow-up ghost should be rendered. {@code -1} when there is no
 *  active follow-up. */
const followUpAnchorIndex = computed<number>(() => {
  if (!props.followUpSuggestion) return -1;
  const anchorMessageId = followUpContext.value?.anchorMessageId;
  if (!anchorMessageId) return -1;
  return allMessages.value.findIndex((message) => message.messageId === anchorMessageId);
});

function onAcceptFollowUp(): void {
  emit('accept-follow-up');
}

/** Sticky chat-process draft for the optimistic streaming bubble. */
const visibleDraft = computed(() => {
  if (!props.chatProcessName) return null;
  const entry = streamingDrafts.value.get(props.chatProcessName);
  // Show as soon as either channel has data — reasoning streams in
  // before the first answer chunk.
  if (!entry || (!entry.content && !entry.thinking)) return null;
  return entry;
});

const visibleWorkerDrafts = computed(() => {
  const out: Array<{ role: ChatRole; content: string; thinking: string; processName: string }> = [];
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
    processName: data.processName,
    role: data.role,
    content: data.content,
    thinking: data.thinking,
    createdAt: data.createdAt,
    meta: data.meta,
    senderUserId: data.senderUserId,
    senderDisplayName: data.senderDisplayName,
    addressedToAgent: data.addressedToAgent,
  });
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
      thinking: '',
      processName: data.processName,
    });
  }
  // Trigger reactivity on the Map.
  streamingDrafts.value = new Map(streamingDrafts.value);
  scrollToBottom();
}

/**
 * Accumulate a reasoning ("thinking") delta into the process draft. The
 * reasoning side-channel streams before the answer content; the draft
 * bubble shows it live in an expanded "thoughts" section, and the
 * canonical {@code chat-message-appended} (with its verbatim `thinking`
 * field) supersedes it on commit.
 */
function appendThinkingChunk(data: ChatMessageChunkData): void {
  const existing = streamingDrafts.value.get(data.processName);
  if (existing && existing.role === data.role) {
    existing.thinking += data.chunk;
  } else {
    streamingDrafts.value.set(data.processName, {
      role: data.role,
      content: '',
      thinking: data.chunk,
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

// ──────────────── Live activity strip ────────────────
//
// Tool calls, provider retries and compaction pings arrive on the
// ephemeral progress side-channel. They are NOT rendered as messages —
// the channel is never persisted, so bubbles would vanish on reload and
// leave gaps in the transcript. Instead they fold into one line above the
// composer; see chatActivity.ts for the reducer and the reasoning.

const activityState = ref(createActivityState());

function onProgress(data: ProcessProgressNotification): void {
  // Reactivity: the reducer mutates in place (it owns the correlation
  // bookkeeping), so hand Vue a fresh reference when something changed.
  if (applyProgress(activityState.value, data, props.chatProcessName, Date.now())) {
    activityState.value = { ...activityState.value };
  }
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

defineExpose({ appendLocalEcho, rollbackLocalEcho, pushWhoActivity, pushCommandActivity });

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
    props.socket.on<ChatMessageChunkData>('chat-message-thinking-chunk', appendThinkingChunk),
    props.socket.on<ProcessModeChangedNotification>(
      'process-mode-changed', onProcessModeChanged),
    props.socket.on<TodosUpdatedNotification>('todos-updated', onTodosUpdated),
    props.socket.on<PlanProposedNotification>('plan-proposed', onPlanProposed),
    props.socket.on<ProcessProgressNotification>('process-progress', onProgress),
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
});

onBeforeUnmount(() => {
  window.removeEventListener('vance-open-wizard', onWizardDeepLink);
  for (const off of subscriptions) off();
  reset();
});

watch(() => props.sessionId, async (newId, oldId) => {
  if (!newId || newId === oldId) return;
  liveMessages.value = [];
  streamingDrafts.value = new Map();
  resetPlanModeState();
  // Another session's tool calls must not linger in the strip.
  activityState.value = createActivityState();
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
    if (isWorkerMessage(m)) return false;
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
    <header class="px-6 py-3 border-b border-base-300 bg-base-100 flex items-center gap-3">
      <SessionHeader
        :session-id="sessionId"
        :can-save="canExportConversation"
        :exporting="exporting"
        @archived="emit('leave')"
        @deleted="emit('leave')"
        @save="onSaveConversation"
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
        <VAlert
          v-if="exportFeedback"
          :variant="exportFeedback.kind"
        >{{ exportFeedback.message }}</VAlert>
        <div v-if="historyLoading" class="text-sm opacity-60">
          {{ $t('chat.historyLoading') }}
        </div>
        <VAlert v-else-if="historyError" variant="error">{{ historyError }}</VAlert>

        <!-- Ephemeral roster activity that arrived before any message
             — non-persistent, see planning/multi-user-sessions.md §7.
             Subsequent join/leave/who events render inline after the
             message they followed, so the feed stays anchored to the
             chat flow instead of sliding down on every new bubble. -->
        <div
          v-for="evt in leadingActivityEvents"
          :key="evt.id"
          class="flex items-center gap-2 text-xs opacity-60 my-2"
        >
          <div class="flex-1 border-t border-base-300" />
          <span v-if="evt.kind === 'who'">
            <span aria-hidden="true">👥</span>
            <span class="ml-1">{{ _('chat.activity.whoHeader') }}</span>
            <span class="font-medium ml-1">{{ evt.displayName }}</span>
          </span>
          <span v-else-if="evt.kind === 'command'" class="font-mono">{{ evt.displayName }}</span>
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

        <template v-for="(msg, idx) in allMessages" :key="msg.messageId">
          <MessageBubble
            :role="String(msg.role)"
            :content="msg.content"
            :thinking="msg.thinking"
            :created-at="msg.createdAt"
            :worker="isWorkerMessage(msg)"
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
          <div
            v-for="evt in activityEventsAfter(msg.messageId)"
            :key="evt.id"
            class="flex items-center gap-2 text-xs opacity-60 my-2"
          >
            <div class="flex-1 border-t border-base-300" />
            <span v-if="evt.kind === 'who'">
              <span aria-hidden="true">👥</span>
              <span class="ml-1">{{ _('chat.activity.whoHeader') }}</span>
              <span class="font-medium ml-1">{{ evt.displayName }}</span>
            </span>
            <span v-else-if="evt.kind === 'command'" class="font-mono">{{ evt.displayName }}</span>
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
        </template>

        <MessageBubble
          v-if="visibleDraft"
          :role="String(visibleDraft.role)"
          :content="visibleDraft.content"
          :thinking="visibleDraft.thinking"
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

    <ChatActivityStrip
      :state="activityState"
      :suppressed="historyLoading"
    />

    <PlanModeIndicator
      :mode="chatProcessMode"
      :todos="chatTodos"
      :plan-meta="planMeta"
    />
  </div>
</template>
